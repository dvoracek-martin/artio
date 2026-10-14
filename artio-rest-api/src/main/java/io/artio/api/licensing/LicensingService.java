package io.artio.api.licensing;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Local request policy and transactionally persisted last-known-good state for one installation. */
public final class LicensingService {
    private static final String SELECT = "SELECT installation_id,signed_document,revision,last_refresh_attempt_at,last_refresh_success_at,refresh_status FROM artio_license_state WHERE singleton_id=true";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Map<String, PublicKey> keys;
    private final boolean trustConfigurationValid;
    private final Clock clock;
    private final LicenseRefreshClient refreshClient;
    private final String refreshUrl;
    private final String refreshCredentialFile;
    private final AtomicReference<Snapshot> cached = new AtomicReference<>();
    private final AtomicLong nextLocalReload = new AtomicLong();
    private final AtomicBoolean refreshing = new AtomicBoolean();

    public LicensingService(JdbcTemplate jdbc, TransactionTemplate transactions, Map<String, PublicKey> keys,
            boolean trustConfigurationValid, Clock clock, LicenseRefreshClient refreshClient,
            String refreshUrl, String refreshCredentialFile) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.keys = Map.copyOf(keys);
        this.trustConfigurationValid = trustConfigurationValid;
        this.clock = clock;
        this.refreshClient = refreshClient;
        this.refreshUrl = refreshUrl == null ? "" : refreshUrl.strip();
        this.refreshCredentialFile = refreshCredentialFile;
        reload();
    }

    public boolean refreshConfigured() { return !refreshUrl.isBlank(); }

    public boolean allows(EnterpriseFeature feature) {
        reloadIfDue();
        VerifiedEntitlement grant = cached.get().grant();
        return grant != null && grant.allows(feature, clock.instant());
    }

    public void require(EnterpriseFeature feature) {
        if (!allows(feature)) throw new FeatureNotLicensedException(feature);
    }

    public Status status() {
        reloadIfDue();
        Snapshot snapshot = cached.get();
        VerifiedEntitlement grant = snapshot.grant();
        Instant now = clock.instant();
        Set<EnterpriseFeature> available = grant == null ? Set.of() : grant.features().stream()
                .filter(feature -> grant.allows(feature, now)).collect(Collectors.toUnmodifiableSet());
        String state = grant == null ? "COMMUNITY" : grant.stateAt(now).name();
        return new Status(snapshot.row().installationId(), available.isEmpty() ? "COMMUNITY" : "ENTERPRISE", state,
                available, grant == null ? null : grant.expiresAt(), grant == null ? null : grant.graceUntil(),
                snapshot.verificationStatus(), refreshConfigured(), refreshConfigured() ? snapshot.row().refreshStatus() : "NOT_CONFIGURED",
                snapshot.row().lastAttempt(), snapshot.row().lastSuccess(), refreshing.get());
    }

    public Status install(byte[] document, UUID actor) {
        install(document, actor, false);
        return status();
    }

    private synchronized void install(byte[] document, UUID actor, boolean fromRefresh) {
        if (!trustConfigurationValid || keys.isEmpty()) throw new IllegalArgumentException("License verification trust is not configured");
        if (document == null || document.length > SignedEntitlementVerifier.MAX_DOCUMENT_BYTES)
            throw new IllegalArgumentException("Invalid signed entitlement");
        Instant now = clock.instant();
        java.sql.Timestamp timestamp = java.sql.Timestamp.from(now);
        transactions.executeWithoutResult(transaction -> {
            Row previous = readRow(true);
            var verifier = new SignedEntitlementVerifier(keys, previous.installationId());
            var policy = new EntitlementPolicy(verifier);
            if (previous.document() != null) {
                // Untrusted/corrupt saved bytes must never confer a grant or prevent a correctly signed replacement.
                try { policy.accept(previous.document().getBytes(StandardCharsets.UTF_8), now); }
                catch (IllegalArgumentException invalidPrevious) { /* no trusted previous grant */ }
            }
            VerifiedEntitlement grant = policy.validateReplacement(document, now);
            String encoded = new String(document, StandardCharsets.UTF_8);
            if (fromRefresh) {
                jdbc.update("UPDATE artio_license_state SET signed_document=?,revision=revision+1,refresh_status='SUCCESS',last_refresh_success_at=?,updated_at=? WHERE singleton_id=true", encoded, timestamp, timestamp);
            } else {
                jdbc.update("UPDATE artio_license_state SET signed_document=?,revision=revision+1,updated_at=? WHERE singleton_id=true", encoded, timestamp);
            }
            jdbc.update("INSERT INTO audit_logs(id,user_id,action,path,status,occurred_at) VALUES (?,?,?,?,200,?)",
                    UUID.randomUUID(), actor, fromRefresh ? "LICENSE_REFRESH" : "LICENSE_INSTALL",
                    fromRefresh ? "/api/v1/admin/system/license/refresh" : "/api/v1/admin/system/license", timestamp);
            Snapshot committed = new Snapshot(readRow(false), grant, "VALID");
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { publish(committed); }
                    });
        });
    }

    /** Called only by the controlled scheduler or an explicit owner action, never by allows/require/status. */
    public Status refresh(UUID actor) {
        jdbc.update("INSERT INTO audit_logs(id,user_id,action,path,status,occurred_at) VALUES (?,?,'LICENSE_CHECK','/api/v1/admin/system/license/refresh',200,?)",
                UUID.randomUUID(), java.util.Objects.requireNonNull(actor), java.sql.Timestamp.from(clock.instant()));
        return refresh();
    }

    public Status refresh() {
        if (!refreshConfigured() || !refreshing.compareAndSet(false, true)) return status();
        try {
            jdbc.update("UPDATE artio_license_state SET last_refresh_attempt_at=?,refresh_status='IN_PROGRESS' WHERE singleton_id=true", java.sql.Timestamp.from(clock.instant()));
            if (!trustConfigurationValid || keys.isEmpty()) throw new LicenseRefreshClient.RefreshFailure("INVALID_CONFIGURATION");
            byte[] document = refreshClient.fetch(refreshUrl, refreshCredentialFile);
            try { install(document, null, true); }
            catch (IllegalArgumentException invalid) { markRefreshFailure("INVALID_RESPONSE"); }
        } catch (LicenseRefreshClient.RefreshFailure failure) { markRefreshFailure(failure.status()); }
        catch (Exception unavailable) { markRefreshFailure("UNAVAILABLE"); }
        finally {
            refreshing.set(false);
            try { reload(); } catch (org.springframework.dao.DataAccessException unavailable) { /* retain verified memory cache */ }
        }
        return status();
    }

    private void markRefreshFailure(String code) {
        try { jdbc.update("UPDATE artio_license_state SET refresh_status=? WHERE singleton_id=true", code); }
        catch (org.springframework.dao.DataAccessException unavailable) { /* failure must not erase the local grant */ }
    }

    private void reloadIfDue() {
        long now = System.nanoTime();
        long due = nextLocalReload.get();
        if (now - due < 0 || !nextLocalReload.compareAndSet(due, now + Duration.ofSeconds(60).toNanos())) return;
        try { reload(); }
        catch (org.springframework.dao.DataAccessException unavailable) { /* cached signed deadlines still apply */ }
    }

    public synchronized void reload() {
        Row row = readRow(false);
        Snapshot previous = cached.get();
        VerifiedEntitlement grant = null;
        String verification = trustConfigurationValid ? keys.isEmpty() ? "TRUST_NOT_CONFIGURED" : "EMPTY" : "INVALID_CONFIGURATION";
        if (row.document() != null && trustConfigurationValid && !keys.isEmpty()) {
            try {
                grant = new SignedEntitlementVerifier(keys, row.installationId()).verify(row.document().getBytes(StandardCharsets.UTF_8), clock.instant());
                verification = "VALID";
            } catch (IllegalArgumentException invalid) {
                verification = "INVALID";
                if (previous != null && previous.row().installationId().equals(row.installationId())) grant = previous.grant();
            }
        }
        publish(new Snapshot(row, grant, verification));
    }

    private void publish(Snapshot next) {
        cached.updateAndGet(previous -> previous != null
                && previous.row().installationId().equals(next.row().installationId())
                && previous.row().revision() > next.row().revision() ? previous : next);
        nextLocalReload.set(System.nanoTime() + Duration.ofSeconds(60).toNanos());
    }

    private Row readRow(boolean lock) {
        return jdbc.queryForObject(SELECT + (lock ? " FOR UPDATE" : ""), (rs, number) -> new Row(
                rs.getObject("installation_id", UUID.class), rs.getString("signed_document"), rs.getLong("revision"),
                rs.getTimestamp("last_refresh_attempt_at") == null ? null : rs.getTimestamp("last_refresh_attempt_at").toInstant(),
                rs.getTimestamp("last_refresh_success_at") == null ? null : rs.getTimestamp("last_refresh_success_at").toInstant(), rs.getString("refresh_status")));
    }

    private record Row(UUID installationId, String document, long revision, Instant lastAttempt, Instant lastSuccess, String refreshStatus) { }
    private record Snapshot(Row row, VerifiedEntitlement grant, String verificationStatus) { }
    public record Status(UUID installationId, String edition, String state, Set<EnterpriseFeature> features,
            Instant expiresAt, Instant graceUntil, String verificationStatus, boolean refreshConfigured,
            String refreshStatus, Instant lastRefreshAttemptAt, Instant lastRefreshSuccessAt, boolean refreshInProgress) { }
}
