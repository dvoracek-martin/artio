package io.artio.api.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class LicensingRuntimeIntegrationTests {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @TempDir static Path tlsDirectory;
    @TempDir Path temporary;
    private static SSLContext serverTls;
    private static SSLContext clientTls;
    private static KeyPair issuer;
    private static final Instant START = Instant.parse("2026-10-15T00:00:00Z");
    private final MutableClock clock = new MutableClock(START);
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeAll
    static void initializeDatabaseAndEphemeralTls() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        issuer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path keystorePath = tlsDirectory.resolve("fixture.p12");
        String tool = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        String password = "tls-fixture-only-password";
        var keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", tool).toString(),
                "-genkeypair", "-alias", "localhost", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", keystorePath.toString(), "-storepass", password,
                "-keypass", password, "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-noprompt")
                .redirectErrorStream(true).start();
        if (!keytool.waitFor(30, TimeUnit.SECONDS)) { keytool.destroyForcibly(); throw new AssertionError("Ephemeral TLS key generation timed out"); }
        assertThat(keytool.exitValue()).as("Ephemeral test TLS certificate generation").isZero();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keystorePath)) { store.load(input, password.toCharArray()); }
        var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password.toCharArray());
        serverTls = SSLContext.getInstance("TLS");
        serverTls.init(keys.getKeyManagers(), null, null);
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, trust.getTrustManagers(), null);
    }

    @BeforeEach
    void isolatedState() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.update("UPDATE artio_license_state SET installation_id=gen_random_uuid(),signed_document=NULL,revision=0,last_refresh_attempt_at=NULL,last_refresh_success_at=NULL,refresh_status='NEVER'");
    }

    @Test
    void persistsSignedGrantAndIdentityAcrossRestartAndUsesNoNetworkOnRequests() throws Exception {
        try (var endpoint = new TlsEndpoint()) {
            var service = service(endpoint, "", false);
            UUID identity = service.status().installationId();
            UUID actor = UUID.randomUUID();
            byte[] original = signed(identity, START, List.of("LOYALTY"));
            service.install(original, actor);
            for (int i = 0; i < 10; i++) { service.require(EnterpriseFeature.LOYALTY); service.status(); }
            assertThat(endpoint.requests.get()).isZero();
            var restarted = service(endpoint, "", false);
            assertThat(restarted.status().installationId()).isEqualTo(identity);
            assertThat(restarted.allows(EnterpriseFeature.LOYALTY)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE user_id=? AND action='LICENSE_INSTALL'", Integer.class, actor)).isEqualTo(1);
            clock.now.set(START.plusSeconds(7200));
            assertThat(restarted.status().state()).isEqualTo("EXPIRED");
            assertThat(restarted.status().features()).isEmpty();
            assertThat(service(endpoint, "", false).allows(EnterpriseFeature.LOYALTY)).isFalse();
        }
    }

    @Test
    void refreshesOverRealHttpsWithFileCredentialAndPublishesOnlySafeStatus() throws Exception {
        try (var endpoint = new TlsEndpoint()) {
            String token = "integration-only-refresh-credential";
            Path credential = Files.writeString(temporary.resolve("refresh-token"), token);
            var service = service(endpoint, credential.toString(), true);
            UUID identity = service.status().installationId();
            service.install(signed(identity, START, List.of("LOYALTY")), UUID.randomUUID());
            clock.now.set(START.plusSeconds(2));
            endpoint.reply.set(new Reply(Mode.NORMAL, signed(identity, START.plusSeconds(1), List.of("LOYALTY", "SUBSCRIPTIONS"))));
            var result = service.refresh(UUID.randomUUID());
            assertThat(result.refreshStatus()).isEqualTo("SUCCESS");
            assertThat(result.features()).containsExactlyInAnyOrder(EnterpriseFeature.LOYALTY, EnterpriseFeature.SUBSCRIPTIONS);
            assertThat(result.lastRefreshSuccessAt()).isEqualTo(clock.instant());
            assertThat(endpoint.authorization.get()).isEqualTo("Bearer " + token);
            assertThat(service(endpoint, "", false).allows(EnterpriseFeature.SUBSCRIPTIONS)).isTrue();
            String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(result);
            assertThat(json).doesNotContain(token, "signature", "payload", "licenseId", "keyId", endpoint.url());
        }
    }

    @Test
    void invalidResponseRedirectOversizeAndSlowBodyRetainLastGoodDeadlines() throws Exception {
        try (var endpoint = new TlsEndpoint()) {
            var service = service(endpoint, "", true);
            byte[] original = signed(service.status().installationId(), START, List.of("LOYALTY"));
            service.install(original, UUID.randomUUID());
            for (Mode mode : List.of(Mode.INVALID, Mode.REDIRECT, Mode.OVERSIZE, Mode.SLOW, Mode.ERROR)) {
                endpoint.reply.set(new Reply(mode, new byte[0]));
                var result = service.refresh();
                assertThat(result.refreshStatus()).as(mode.name()).isEqualTo(
                        mode == Mode.INVALID || mode == Mode.OVERSIZE ? "INVALID_RESPONSE" : "UNAVAILABLE");
                assertThat(service.allows(EnterpriseFeature.LOYALTY)).isTrue();
                assertThat(result.graceUntil()).isEqualTo(START.plusSeconds(7200));
                assertThat(jdbc.queryForObject("SELECT signed_document FROM artio_license_state", String.class))
                        .isEqualTo(new String(original, StandardCharsets.UTF_8));
            }
            assertThat(endpoint.requests.get()).isEqualTo(5); // Redirect target must never be followed.
            clock.now.set(START.plusSeconds(7200));
            assertThat(service.allows(EnterpriseFeature.LOYALTY)).isFalse();
        }
    }

    @Test
    void rejectsUntrustedTlsAndInvalidEndpointConfigurationWithoutErasingGrant() throws Exception {
        try (var endpoint = new TlsEndpoint()) {
            var untrusted = new LicenseRefreshClient(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), Duration.ofSeconds(2));
            var service = new LicensingService(jdbc, transactions, Map.of("issuer", issuer.getPublic()), true, clock,
                    untrusted, endpoint.url(), "");
            service.install(signed(service.status().installationId(), START, List.of("LOYALTY")), UUID.randomUUID());
            assertThat(service.refresh().refreshStatus()).isEqualTo("UNAVAILABLE");
            assertThat(service.allows(EnterpriseFeature.LOYALTY)).isTrue();
            var client = endpoint.client();
            for (String invalid : List.of("http://localhost/", "https://user@localhost/", "https://localhost/#fragment")) {
                assertThatThrownBy(() -> client.fetch(invalid, "")).isInstanceOf(LicenseRefreshClient.RefreshFailure.class)
                        .hasMessage("License refresh failed").hasNoCause();
            }
            assertThat(endpoint.requests.get()).isZero();
        }
    }

    @Test
    void rollsBackDocumentAndMemoryOnAuditFailureAndRejectsStaleReplacementAcrossProcesses() throws Exception {
        try (var endpoint = new TlsEndpoint()) {
            var first = service(endpoint, "", false);
            byte[] original = signed(first.status().installationId(), START, List.of("LOYALTY"));
            first.install(original, UUID.randomUUID());
            clock.now.set(START.plusSeconds(2));
            byte[] removal = signed(first.status().installationId(), START.plusSeconds(1), List.of());
            transactions.executeWithoutResult(transaction -> {
                first.install(removal, UUID.randomUUID());
                transaction.setRollbackOnly();
            });
            assertThat(first.allows(EnterpriseFeature.LOYALTY)).isTrue();
            assertThat(jdbc.queryForObject("SELECT signed_document FROM artio_license_state", String.class)).isEqualTo(new String(original, StandardCharsets.UTF_8));
            jdbc.execute("CREATE FUNCTION reject_fixture_license_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='LICENSE_INSTALL' THEN RAISE EXCEPTION 'fixture audit failure'; END IF; RETURN NEW; END $$");
            jdbc.execute("CREATE TRIGGER reject_fixture_license_audit BEFORE INSERT ON audit_logs FOR EACH ROW EXECUTE FUNCTION reject_fixture_license_audit()");
            try {
                assertThatThrownBy(() -> first.install(removal, UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThat(first.allows(EnterpriseFeature.LOYALTY)).isTrue();
                assertThat(jdbc.queryForObject("SELECT signed_document FROM artio_license_state", String.class)).isEqualTo(new String(original, StandardCharsets.UTF_8));
            } finally {
                jdbc.execute("DROP TRIGGER reject_fixture_license_audit ON audit_logs");
                jdbc.execute("DROP FUNCTION reject_fixture_license_audit()");
            }
            var second = service(endpoint, "", false);
            second.install(removal, UUID.randomUUID());
            assertThatThrownBy(() -> first.install(original, UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
            first.reload();
            assertThat(first.allows(EnterpriseFeature.LOYALTY)).isFalse();
            assertThat(service(endpoint, "", false).allows(EnterpriseFeature.LOYALTY)).isFalse();
        }
    }

    @Test
    void invalidTrustConfigurationKeepsCommunityAndDoesNotConferEnterprise() throws Exception {
        try (var endpoint = new TlsEndpoint()) {
            var configured = service(endpoint, "", false);
            configured.install(signed(configured.status().installationId(), START, List.of("LOYALTY")), UUID.randomUUID());
            var unavailable = new LicensingService(jdbc, transactions, Map.of(), false, clock, endpoint.client(), endpoint.url(), "");
            assertThat(unavailable.status().edition()).isEqualTo("COMMUNITY");
            assertThat(unavailable.status().verificationStatus()).isEqualTo("INVALID_CONFIGURATION");
            assertThat(unavailable.allows(EnterpriseFeature.LOYALTY)).isFalse();
            assertThat(unavailable.refresh().refreshStatus()).isEqualTo("INVALID_CONFIGURATION");
            assertThat(endpoint.requests.get()).isZero();
        }
    }

    private LicensingService service(TlsEndpoint endpoint, String credentialFile, boolean refresh) {
        return new LicensingService(jdbc, transactions, Map.of("issuer", issuer.getPublic()), true, clock,
                endpoint.client(), refresh ? endpoint.url() : "", credentialFile);
    }

    private static byte[] signed(UUID installation, Instant issued, List<String> features) throws Exception {
        var claims = new LinkedHashMap<String, Object>();
        claims.put("version", 1); claims.put("licenseId", "6ce81dab-9e43-42e3-8a6b-04276f4e2048");
        claims.put("installationId", installation.toString()); claims.put("issuedAt", issued.getEpochSecond());
        claims.put("notBefore", issued.getEpochSecond()); claims.put("expiresAt", START.plusSeconds(3600).getEpochSecond());
        claims.put("graceUntil", START.plusSeconds(7200).getEpochSecond()); claims.put("features", features);
        var mapper = new ObjectMapper();
        byte[] payload = mapper.writeValueAsBytes(claims);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(issuer.getPrivate()); signature.update(payload);
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return mapper.writeValueAsBytes(Map.of("keyId", "issuer", "payload", encoder.encodeToString(payload), "signature", encoder.encodeToString(signature.sign())));
    }

    private enum Mode { NORMAL, INVALID, REDIRECT, OVERSIZE, SLOW, ERROR }
    private record Reply(Mode mode, byte[] document) { }
    private static final class TlsEndpoint implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger();
        final AtomicReference<String> authorization = new AtomicReference<>();
        final AtomicReference<Reply> reply = new AtomicReference<>(new Reply(Mode.INVALID, new byte[0]));
        final HttpsServer server;
        final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "license-tls-fixture"); thread.setDaemon(true); return thread;
        });
        TlsEndpoint() throws Exception {
            server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(serverTls));
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                Reply response = reply.get();
                try (exchange) {
                    if (response.mode() == Mode.REDIRECT) {
                        exchange.getResponseHeaders().set("Location", url() + "/followed");
                        exchange.sendResponseHeaders(302, -1); return;
                    }
                    if (response.mode() == Mode.ERROR) { exchange.sendResponseHeaders(503, -1); return; }
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    if (response.mode() == Mode.SLOW) {
                        exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
                        try { Thread.sleep(4000); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                        return;
                    }
                    byte[] body = response.mode() == Mode.OVERSIZE ? new byte[65_537]
                            : response.mode() == Mode.INVALID ? "{not a signed license".getBytes(StandardCharsets.UTF_8) : response.document();
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } catch (java.io.IOException disconnected) { /* bounded client cancellation is expected */ }
            });
            server.start();
        }
        String url() { return "https://localhost:" + server.getAddress().getPort() + "/license"; }
        LicenseRefreshClient client() {
            return new LicenseRefreshClient(HttpClient.newBuilder().sslContext(clientTls)
                    .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build(), Duration.ofSeconds(2));
        }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }

    private static final class MutableClock extends Clock {
        final AtomicReference<Instant> now;
        MutableClock(Instant instant) { now = new AtomicReference<>(instant); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
    }
}
