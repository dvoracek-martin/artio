package io.artio.api.extensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.artio.extensions.ExtensionRuntimePort;
import io.artio.extensions.PluginManifest;
import io.artio.api.security.EncryptionService;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;

/** Executes durable install jobs outside the request thread and without holding a database transaction over Docker I/O. */
@Component
@ConditionalOnProperty(prefix = "artio.workers", name = "enabled", havingValue = "true")
public class ExtensionInstallationJobWorker {
    private static final Logger log = LoggerFactory.getLogger(ExtensionInstallationJobWorker.class);
    private static final TypeReference<List<java.util.Map<String, String>>> IMAGE_LIST = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ObjectProvider<ExtensionRuntimePort> runtimeProvider;
    private final ExtensionRegistryCredentialService registryCredentials;
    private final EncryptionService encryption;
    private final OciArchiveInspector archiveInspector;
    private final OciArchiveQuarantineStore quarantine;
    private final long leaseDurationMillis;
    private final long leaseRenewalMillis;
    private final ScheduledExecutorService leaseRenewals;
    private final TransactionTemplate transactions;

    public ExtensionInstallationJobWorker(JdbcTemplate jdbc, ObjectMapper mapper,
            ObjectProvider<ExtensionRuntimePort> runtimeProvider,
            ExtensionRegistryCredentialService registryCredentials,
            OciArchiveInspector archiveInspector,
            OciArchiveQuarantineStore quarantine,
            PlatformTransactionManager transactionManager,
            @Value("${artio.extensions.installations.lease-duration-ms:1800000}") long leaseDurationMillis,
            @Value("${artio.extensions.installations.lease-renewal-ms:300000}") long leaseRenewalMillis) {
        this(jdbc, mapper, runtimeProvider, registryCredentials, null, archiveInspector, quarantine,
                transactionManager, leaseDurationMillis, leaseRenewalMillis);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ExtensionInstallationJobWorker(JdbcTemplate jdbc, ObjectMapper mapper,
            ObjectProvider<ExtensionRuntimePort> runtimeProvider,
            ExtensionRegistryCredentialService registryCredentials, EncryptionService encryption,
            OciArchiveInspector archiveInspector,
            OciArchiveQuarantineStore quarantine,
            PlatformTransactionManager transactionManager,
            @Value("${artio.extensions.installations.lease-duration-ms:1800000}") long leaseDurationMillis,
            @Value("${artio.extensions.installations.lease-renewal-ms:300000}") long leaseRenewalMillis) {
        if (leaseDurationMillis < 1_000 || leaseRenewalMillis < 250 || leaseRenewalMillis * 2 >= leaseDurationMillis) {
            throw new IllegalArgumentException("Installation job lease renewal must run at least twice before lease expiry");
        }
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.runtimeProvider = runtimeProvider;
        this.registryCredentials = registryCredentials;
        this.encryption = encryption;
        this.archiveInspector = archiveInspector;
        this.quarantine = quarantine;
        this.leaseDurationMillis = leaseDurationMillis;
        this.leaseRenewalMillis = leaseRenewalMillis;
        this.transactions = new TransactionTemplate(transactionManager);
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "artio-installation-lease-renewal");
            thread.setDaemon(true);
            return thread;
        };
        this.leaseRenewals = Executors.newScheduledThreadPool(2, threadFactory);
    }

    @PreDestroy
    void stopLeaseRenewals() {
        leaseRenewals.shutdownNow();
    }

    @Scheduled(fixedDelayString = "${artio.extensions.installations.poll-interval-ms:1000}")
    public void runPending() {
        List<InstallJob> jobs = jdbc.query("""
                UPDATE extension_installation_jobs SET state='POLICY_CHECK',
                    attempt_count=attempt_count+CASE WHEN state='WAITING_FOR_OBLIGATIONS' THEN 0 ELSE 1 END,
                    lease_token=md5(job_id::text || clock_timestamp()::text || random()::text)::uuid,
                    lease_until=now()+(? * interval '1 millisecond'),updated_at=now()
                WHERE job_id IN (
                    SELECT job_id FROM extension_installation_jobs
                    WHERE state IN ('REQUESTED','WAITING_FOR_OBLIGATIONS')
                       OR (state IN ('POLICY_CHECK','VERIFYING_SOURCE','DOWNLOADING_OR_IMPORTING','VERIFYING_ARTIFACTS','REGISTERING_RELEASE') AND lease_until<now())
                    ORDER BY created_at LIMIT 4 FOR UPDATE SKIP LOCKED
                )
                RETURNING job_id,plugin_id,instance_id,runtime_identity,manifest_snapshot::text,permission_snapshot::text,image_snapshot::text,lease_token,source_kind,
                    operation_kind,source_release_id,previous_release_id,previous_manifest_snapshot::text,previous_lifecycle_state,
                    configuration_snapshot::text,
                    (SELECT quarantine_name FROM extension_local_installation_archives a WHERE a.job_id=extension_installation_jobs.job_id) AS quarantine_name,
                    (SELECT archive_sha256 FROM extension_local_installation_archives a WHERE a.job_id=extension_installation_jobs.job_id) AS archive_sha256
                """, (rs, row) -> new InstallJob(rs.getObject("job_id", UUID.class), rs.getString("plugin_id"),
                rs.getObject("instance_id", UUID.class), rs.getString("runtime_identity"), rs.getString("manifest_snapshot"),
                rs.getString("permission_snapshot"), rs.getString("image_snapshot"), rs.getObject("lease_token", UUID.class),
                rs.getString("source_kind"), rs.getString("operation_kind"), (Long) rs.getObject("source_release_id"),
                (Long) rs.getObject("previous_release_id"), rs.getString("previous_manifest_snapshot"),
                rs.getString("previous_lifecycle_state"), rs.getString("configuration_snapshot"),
                rs.getString("quarantine_name"), rs.getString("archive_sha256")), leaseDurationMillis);
        var heartbeats = jobs.stream()
                .map(job -> leaseRenewals.scheduleWithFixedDelay(
                        () -> renewLease(job), leaseRenewalMillis, leaseRenewalMillis, TimeUnit.MILLISECONDS))
                .toList();
        try {
            jobs.forEach(this::installClaimed);
        } finally {
            heartbeats.forEach(heartbeat -> heartbeat.cancel(false));
        }
    }

    private void renewLease(InstallJob job) {
        try {
            int renewed = jdbc.update("UPDATE extension_installation_jobs SET lease_until=now()+(? * interval '1 millisecond'),updated_at=now() "
                            + "WHERE job_id=? AND lease_token=? AND lease_until>now() "
                            + "AND state IN ('POLICY_CHECK','VERIFYING_SOURCE','DOWNLOADING_OR_IMPORTING','VERIFYING_ARTIFACTS','REGISTERING_RELEASE')",
                    leaseDurationMillis, job.jobId(), job.leaseToken());
            if (renewed == 0) {
                log.debug("Installation job lease is no longer owned by worker: {}", job.jobId());
            }
        } catch (Exception failure) {
            log.warn("Installation job lease renewal failed for job {} ({})",
                    job.jobId(), failure.getClass().getSimpleName());
        }
    }

    private void installClaimed(InstallJob job) {
        PluginManifest manifest = null;
        boolean replacementApplied = false;
        try {
            manifest = mapper.readValue(job.manifestJson(), PluginManifest.class);
            if (!job.pluginId().equals(manifest.id())) throw new IllegalStateException("Installation manifest identity changed");
            ExtensionSecurityPolicy.requireAllowed(jdbc, manifest);
            ExtensionRuntimePort runtime = runtimeProvider.getIfAvailable();
            if (runtime == null) throw new ExtensionRuntimePort.ExtensionUnavailableException("Plugin runtime is unavailable");
            if (!transition(job, "VERIFYING_SOURCE")) return;
            List<String> permissionSnapshot = mapper.readValue(job.permissionJson(), PermissionSnapshot.class).permissions();
            List<java.util.Map<String, String>> imageSnapshot = mapper.readValue(job.imageJson(), IMAGE_LIST);
            if (!permissionSnapshot.equals(manifest.permissions())) throw new IllegalStateException("Installation permission snapshot changed");
            var expectedImages = new java.util.ArrayList<java.util.Map<String, String>>();
            expectedImages.add(java.util.Map.of("id", "primary", "image", manifest.image()));
            manifest.services().forEach(service -> expectedImages.add(java.util.Map.of("id", service.id(), "image", service.image())));
            if (!imageSnapshot.equals(expectedImages) || expectedImages.stream().anyMatch(image ->
                    !image.get("image").matches(".+@sha256:[a-f0-9]{64}"))) {
                throw new IllegalStateException("Installation image snapshot is invalid or changed");
            }
            if (!transition(job, "DOWNLOADING_OR_IMPORTING")) return;
            if (!"INSTALL".equals(job.operationKind())) {
                PluginManifest previous = mapper.readValue(job.previousManifestJson(), PluginManifest.class);
                if (job.instanceId() == null || job.previousReleaseId() == null || job.releaseId() == null
                        || !job.pluginId().equals(previous.id())) {
                    throw new IllegalStateException("Plugin release operation snapshot is incomplete");
                }
                Long installedRelease = jdbc.queryForObject("SELECT release_id FROM extension_plugin_instances "
                        + "WHERE instance_id=? AND plugin_id=?", Long.class, job.instanceId(), job.pluginId());
                if (!job.previousReleaseId().equals(installedRelease)) {
                    throw new IllegalStateException("Plugin instance release changed before its queued operation");
                }
                if (unresolvedObligations(job.instanceId()) > 0) {
                    waitForObligations(job);
                    return;
                }
                runtime.disableInstance(job.runtimeIdentity(), job.pluginId());
                var auth = registryCredentials.credentialsFor(manifest);
                runtime.updateInstance(job.runtimeIdentity(), previous, manifest, auth);
                replacementApplied = true;
                ConfigurationSnapshot snapshot = mapper.readValue(job.configurationJson(), ConfigurationSnapshot.class);
                int credentialGeneration = jdbc.queryForObject("SELECT credential_generation FROM extension_plugins WHERE plugin_id=?",
                        Integer.class, job.pluginId());
                runtime.enableInstance(job.runtimeIdentity(), manifest, credentialGeneration,
                        runtimeConfiguration(snapshot));
                runtime.disableInstance(job.runtimeIdentity(), job.pluginId());
            } else if ("ARCHIVE".equals(job.sourceKind())) {
                Path archive = archivePath(job.quarantineName());
                if (!job.archiveSha256().equals(sha256(archive))) throw new IllegalStateException("Quarantined archive digest changed");
                OciArchiveInspector.Inspection inspection = archiveInspector.inspect(archive, manifest);
                Map<String, List<String>> inventory = new java.util.LinkedHashMap<>();
                inspection.images().forEach((digest, image) -> inventory.put(digest, image.configDigests()));
                Map<String, Object> provenance = Map.of(
                        "sourceFormat", inspection.sourceFormat(),
                        "imageReferenceToConfigDigests", inventory,
                        "verifiedManifestDigests", inspection.sourceFormat().equals("OCI_LAYOUT")
                                ? List.copyOf(inspection.images().keySet()) : List.of());
                jdbc.update("UPDATE extension_local_installation_archives SET verified_inventory=?::jsonb WHERE job_id=?",
                        mapper.writeValueAsString(provenance), job.jobId());
                runtime.importLocalArchive(archive, inventory);
                if (job.instanceId() == null) runtime.install(manifest);
                else runtime.installInstance(job.runtimeIdentity(), manifest);
            } else {
                var auth = registryCredentials.credentialsFor(manifest);
                if (job.instanceId() == null) runtime.install(manifest, auth);
                else runtime.installInstance(job.runtimeIdentity(), manifest, auth);
            }
            if (!transition(job, "VERIFYING_ARTIFACTS") || !finalizeInstallation(job)) return;
            if ("ARCHIVE".equals(job.sourceKind())) {
                try { quarantine.delete(archivePath(job.quarantineName())); }
                catch (java.io.IOException cleanupFailure) {
                    // Keep the installation complete; a periodic quarantine cleanup can remove stale files safely.
                }
            }
        } catch (ExtensionSecurityPolicy.BannedArtifactException blocked) {
            jdbc.update("UPDATE extension_installation_jobs SET state='SECURITY_BLOCKED',lease_token=NULL,lease_until=NULL,error_code='MARKETPLACE_SECURITY_ADVISORY',"
                            + "completed_at=now(),updated_at=now() WHERE job_id=? AND lease_token=?",
                    job.jobId(), job.leaseToken());
        } catch (Exception failure) {
            String error = failure.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", "");
            if (error.length() > 64) error = error.substring(0, 64);
            int owned = jdbc.update("UPDATE extension_installation_jobs SET state='FAILED',lease_token=NULL,lease_until=NULL,error_code=?,"
                            + "completed_at=now(),updated_at=now() WHERE job_id=? AND lease_token=?",
                    error, job.jobId(), job.leaseToken());
            if (owned != 1) return;
            if (!"INSTALL".equals(job.operationKind()) && job.instanceId() != null) {
                boolean restored = false;
                try {
                    PluginManifest previous = mapper.readValue(job.previousManifestJson(), PluginManifest.class);
                    ExtensionRuntimePort runtime = runtimeProvider.getIfAvailable();
                    if (runtime == null) throw new ExtensionRuntimePort.ExtensionUnavailableException("Plugin runtime is unavailable");
                    runtime.disableInstance(job.runtimeIdentity(), job.pluginId());
                    if (replacementApplied) {
                        if (manifest == null) throw new IllegalStateException("Replacement manifest is unavailable for recovery");
                        runtime.updateInstance(job.runtimeIdentity(), manifest, previous, registryCredentials.credentialsFor(previous));
                    }
                    ConfigurationSnapshot oldSnapshot = currentConfigurationSnapshot(job.instanceId());
                    int credentialGeneration = jdbc.queryForObject("SELECT credential_generation FROM extension_plugins WHERE plugin_id=?",
                            Integer.class, job.pluginId());
                    runtime.enableInstance(job.runtimeIdentity(), previous, credentialGeneration,
                            runtimeConfiguration(oldSnapshot));
                    runtime.disableInstance(job.runtimeIdentity(), job.pluginId());
                    restored = true;
                } catch (Exception recoveryFailure) {
                    log.error("Plugin release recovery failed for job {} ({})", job.jobId(),
                            recoveryFailure.getClass().getSimpleName());
                }
                if (restored) {
                    jdbc.update("UPDATE extension_plugin_instances SET lifecycle_state=?,updated_at=now() "
                                    + "WHERE instance_id=? AND plugin_id=? AND release_id=?",
                            job.previousLifecycleState(), job.instanceId(), job.pluginId(), job.previousReleaseId());
                } else {
                    jdbc.update("UPDATE extension_plugin_instances SET lifecycle_state='FAILED',updated_at=now() WHERE instance_id=?",
                            job.instanceId());
                }
            } else if (job.instanceId() == null) {
                jdbc.update("UPDATE extension_plugins SET lifecycle_state='FAILED',updated_at=now() WHERE plugin_id=?", job.pluginId());
                jdbc.update("UPDATE extension_plugin_instances SET lifecycle_state='FAILED',updated_at=now() "
                        + "WHERE plugin_id=? AND compatibility_mode='LEGACY_V1'", job.pluginId());
            } else {
                jdbc.update("UPDATE extension_plugin_instances SET lifecycle_state='FAILED',updated_at=now() WHERE instance_id=?", job.instanceId());
            }
        }
    }

    private boolean transition(InstallJob job, String state) {
        return jdbc.update("UPDATE extension_installation_jobs SET state=?,updated_at=now() WHERE job_id=? AND lease_token=? AND lease_until>now()",
                state, job.jobId(), job.leaseToken()) == 1;
    }

    private long unresolvedObligations(UUID instanceId) {
        Long count = jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM extension_operation_attempts WHERE instance_id=? AND attempt_state IN ('PENDING','IN_PROGRESS','UNKNOWN','RECONCILING'))
                + (SELECT count(*) FROM extension_event_deliveries WHERE instance_id=? AND state<>'DELIVERED')
                + (SELECT count(*) FROM catalog_media_storage_objects WHERE object_state IN ('ACTIVE','DELETE_PENDING')
                    AND (primary_instance_id=? OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(replica_instance_ids) r(instance_id) WHERE r.instance_id=?)))
                + (SELECT count(*) FROM payments WHERE provider_instance_id=? AND status='PENDING')
                + (SELECT count(*) FROM order_shipments s JOIN orders o ON o.id=s.order_id WHERE s.provider_instance_id=?
                    AND o.status IN ('CONFIRMED','PAID','PARTIALLY_SHIPPED','SHIPPED'))
                + (SELECT count(*) FROM payment_refunds r JOIN payments p ON p.id=r.payment_id
                    WHERE p.provider_instance_id=? AND r.status='PROCESSING')
                """, Long.class, instanceId, instanceId, instanceId, instanceId.toString(), instanceId, instanceId, instanceId);
        return count == null ? 0 : count;
    }

    private Map<String, String> runtimeConfiguration(ConfigurationSnapshot snapshot) {
        if (encryption == null) throw new IllegalStateException("Plugin release configuration encryption is unavailable");
        var values = new java.util.LinkedHashMap<String, String>();
        snapshot.publicConfiguration().forEach((key, value) -> {
            if (value != null) values.put(key, value instanceof String text ? text : String.valueOf(value));
        });
        snapshot.encryptedSecrets().forEach((key, ciphertext) -> values.put(key, encryption.decrypt(ciphertext)));
        return Map.copyOf(values);
    }

    private ConfigurationSnapshot currentConfigurationSnapshot(UUID instanceId) {
        return jdbc.query("SELECT public_configuration::text,encrypted_secrets::text,configuration_generation "
                        + "FROM extension_plugin_instances WHERE instance_id=?",
                rs -> rs.next() ? new ConfigurationSnapshot(
                        readObjectMap(rs.getString(1)), readStringMap(rs.getString(2)), rs.getInt(3)) : null,
                instanceId);
    }

    private Map<String, Object> readObjectMap(String json) {
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception failure) { throw new IllegalStateException("Stored plugin configuration is invalid", failure); }
    }

    private Map<String, String> readStringMap(String json) {
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception failure) { throw new IllegalStateException("Stored plugin secrets are invalid", failure); }
    }

    private ConfigurationSnapshot readConfigurationSnapshot(String json) {
        try { return mapper.readValue(json, ConfigurationSnapshot.class); }
        catch (Exception failure) { throw new IllegalStateException("Stored plugin release configuration is invalid", failure); }
    }

    private String writeJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Plugin release configuration could not be persisted", failure); }
    }

    private void waitForObligations(InstallJob job) {
        jdbc.update("UPDATE extension_installation_jobs SET state='WAITING_FOR_OBLIGATIONS',lease_token=NULL,lease_until=NULL,"
                        + "updated_at=now() WHERE job_id=? AND lease_token=? AND lease_until>now()",
                job.jobId(), job.leaseToken());
    }

    boolean finalizeInstallation(InstallJob job) {
        Boolean finalized = transactions.execute(status -> {
            int claimed = jdbc.update("UPDATE extension_installation_jobs SET state='REGISTERING_RELEASE',updated_at=now() "
                            + "WHERE job_id=? AND lease_token=? AND lease_until>now() AND state='VERIFYING_ARTIFACTS'",
                    job.jobId(), job.leaseToken());
            if (claimed != 1) return false;

            if (job.instanceId() == null) {
                jdbc.update("UPDATE extension_plugins SET lifecycle_state='INSTALLED',updated_at=now() WHERE plugin_id=?", job.pluginId());
                jdbc.update("UPDATE extension_plugin_instances SET lifecycle_state='INSTALLED',updated_at=now() "
                        + "WHERE plugin_id=? AND compatibility_mode='LEGACY_V1' AND lifecycle_state IN ('VALIDATED','CONFIGURED','FAILED')", job.pluginId());
            } else {
                if ("INSTALL".equals(job.operationKind())) {
                    jdbc.update("UPDATE extension_plugin_instances SET lifecycle_state='INSTALLED',updated_at=now() "
                            + "WHERE instance_id=? AND lifecycle_state IN ('CONFIGURED','FAILED')", job.instanceId());
                } else {
                    ConfigurationSnapshot snapshot = readConfigurationSnapshot(job.configurationJson());
                    String resultingLifecycleState = "DRAINING".equals(job.previousLifecycleState())
                            ? "DRAINING" : job.previousLifecycleState();
                    int switched = jdbc.update("UPDATE extension_plugin_instances SET release_id=?,public_configuration=?::jsonb,"
                                    + "encrypted_secrets=?::jsonb,configuration_generation=?,lifecycle_state=?,updated_at=now() "
                                    + "WHERE instance_id=? AND plugin_id=? AND release_id=? AND lifecycle_state IN ('DRAINING','DISABLED','INSTALLED')",
                            job.releaseId(), writeJson(snapshot.publicConfiguration()),
                            writeJson(snapshot.encryptedSecrets()), snapshot.configurationGeneration(), resultingLifecycleState,
                            job.instanceId(), job.pluginId(), job.previousReleaseId());
                    if (switched != 1) {
                        status.setRollbackOnly();
                        return false;
                    }
                }
            }

            int completed = jdbc.update("UPDATE extension_installation_jobs SET state='INSTALLED',lease_token=NULL,lease_until=NULL,error_code=NULL,"
                            + "completed_at=now(),updated_at=now() WHERE job_id=? AND lease_token=? AND lease_until>now() AND state='REGISTERING_RELEASE'",
                    job.jobId(), job.leaseToken());
            if (completed != 1) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        });
        return Boolean.TRUE.equals(finalized);
    }

    private Path archivePath(String quarantineName) {
        Path archive = quarantine.pathFor(quarantineName);
        if (!Files.isRegularFile(archive)) {
            throw new IllegalStateException("Quarantined local archive is missing");
        }
        return archive;
    }

    private static String sha256(Path archive) throws java.io.IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(archive)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) if (count > 0) digest.update(buffer, 0, count);
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    record InstallJob(UUID jobId, String pluginId, UUID instanceId, String runtimeIdentity,
            String manifestJson, String permissionJson, String imageJson, UUID leaseToken, String sourceKind,
            String operationKind, Long releaseId, Long previousReleaseId, String previousManifestJson,
            String previousLifecycleState, String configurationJson, String quarantineName, String archiveSha256) { }
    private record PermissionSnapshot(List<String> permissions) { }
    record ConfigurationSnapshot(Map<String, Object> publicConfiguration, Map<String, String> encryptedSecrets,
            int configurationGeneration) {
        ConfigurationSnapshot {
            publicConfiguration = Map.copyOf(publicConfiguration);
            encryptedSecrets = Map.copyOf(encryptedSecrets);
        }
    }
}
