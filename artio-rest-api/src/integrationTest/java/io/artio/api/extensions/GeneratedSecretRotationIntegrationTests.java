package io.artio.api.extensions;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.ArtioRestApiApplication;
import io.artio.api.security.EncryptionService;
import io.artio.extensions.PluginManifest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL acceptance for secret-safe, serialized generated credential rotation. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = ArtioRestApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class GeneratedSecretRotationIntegrationTests {
    private static final String PLUGIN_ID = "rotation.acceptance";
    private static final String ENCRYPTION_KEY = "integration-test-encryption-key-value-32-bytes-minimum";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired EncryptionService encryption;
    @Autowired TestRestTemplate http;
    private String adminToken;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("artio.security.jwt-secret", () -> "integration-test-jwt-secret-value-32-bytes-minimum");
        registry.add("artio.security.encryption-key", () -> ENCRYPTION_KEY);
        registry.add("artio.setup.token", () -> "integration-test-setup-token-value-32-bytes-minimum");
        registry.add("artio.workers.enabled", () -> "false");
        registry.add("artio.events.kafka.enabled", () -> "false");
        registry.add("artio.events.kafka.consumer-enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @BeforeEach
    void clearFixture() {
        jdbc.update("DELETE FROM extension_audit_events WHERE plugin_id=?", PLUGIN_ID);
        jdbc.update("DELETE FROM extension_plugin_instances WHERE plugin_id=?", PLUGIN_ID);
        jdbc.update("DELETE FROM extension_plugin_releases WHERE plugin_id=?", PLUGIN_ID);
        jdbc.update("DELETE FROM extension_plugins WHERE plugin_id=?", PLUGIN_ID);
        UUID userId = UUID.randomUUID();
        String email = "rotation-admin-" + userId + "@example.test";
        String password = "admin-password-123";
        String totpSecret = io.artio.api.security.Totp.newSecret();
        jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,role,created_at) VALUES (?,?,?,?,?,'ADMIN',now())",
                userId, email, new BCryptPasswordEncoder().encode(password), "Test", "Administrator");
        jdbc.update("INSERT INTO mfa_methods(user_id,encrypted_secret,verified,verified_at) VALUES (?,?,TRUE,now())",
                userId, encryption.encrypt(totpSecret));
        var login = http.postForEntity("/api/v1/auth/login", Map.of("email", email, "password", password,
                "mfaCode", io.artio.api.TestTotp.code(totpSecret, java.time.Instant.now())), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        adminToken = String.valueOf(login.getBody().get("accessToken"));
    }

    @Test
    void rotatesOnlyTheRequestedGeneratedGroupAndNeverReturnsOrAuditsSecretValues() throws Exception {
        UUID instanceId = seedDisabledInstance();
        String oldAccessKey = decryptSecret(instanceId, "runtimeAccessKey");
        String oldSecretKey = decryptSecret(instanceId, "runtimeSecretKey");

        var unauthorized = http.postForEntity(rotationPath(instanceId), null, Map.class);
        assertThat(unauthorized.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var response = adminExchange(instanceId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().toString()).contains("runtimeAccessKey", "runtimeSecretKey", "CONFIGURED")
                .doesNotContain(oldAccessKey, oldSecretKey);
        assertThat(jdbc.queryForObject("SELECT configuration_generation FROM extension_plugin_instances WHERE instance_id=?",
                Integer.class, instanceId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT lifecycle_state FROM extension_plugin_instances WHERE instance_id=?",
                String.class, instanceId)).isEqualTo("CONFIGURED");

        String nextAccessKey = decryptSecret(instanceId, "runtimeAccessKey");
        String nextSecretKey = decryptSecret(instanceId, "runtimeSecretKey");
        assertThat(nextAccessKey).hasSize(20).matches("[A-Za-z0-9_-]+").isNotEqualTo(oldAccessKey);
        assertThat(nextSecretKey).hasSize(40).matches("[A-Za-z0-9_-]+").isNotEqualTo(oldSecretKey);
        assertThat(decryptSecret(instanceId, "managedRootUser")).hasSize(20);
        String audit = jdbc.queryForObject("SELECT details::text FROM extension_audit_events WHERE plugin_id=? AND action=?",
                String.class, PLUGIN_ID, "ROTATE_INSTANCE_SECRETS");
        assertThat(audit).contains("runtime", "runtimeAccessKey", "runtimeSecretKey")
                .doesNotContain(oldAccessKey, oldSecretKey, nextAccessKey, nextSecretKey);
    }

    @Test
    void refusesRotationWhileInstanceIsEnabledAndDoesNotChangeCredentials() throws Exception {
        UUID instanceId = seedDisabledInstance();
        jdbc.update("UPDATE extension_plugin_instances SET lifecycle_state='ENABLED' WHERE instance_id=?", instanceId);
        String oldAccessKey = decryptSecret(instanceId, "runtimeAccessKey");

        var response = adminExchange(instanceId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(decryptSecret(instanceId, "runtimeAccessKey")).isEqualTo(oldAccessKey);
        assertThat(jdbc.queryForObject("SELECT configuration_generation FROM extension_plugin_instances WHERE instance_id=?",
                Integer.class, instanceId)).isZero();
    }

    private UUID seedDisabledInstance() throws Exception {
        var fields = Map.of(
                "mode", new PluginManifest.ConfigField("enum", true, false, "Mode", "", null, List.of("managed", "external"),
                        null, null, null, null),
                "managedRootUser", new PluginManifest.ConfigField("secret", true, true, "Root", "", null, List.of(), 20, 20,
                        null, null, true, 20, null),
                "runtimeAccessKey", new PluginManifest.ConfigField("secret", true, true, "Access", "", null, List.of(), 20, 20,
                        null, null, true, 20, "runtime"),
                "runtimeSecretKey", new PluginManifest.ConfigField("secret", true, true, "Secret", "", null, List.of(), 40, 40,
                        null, null, true, 40, "runtime"));
        String image = "ghcr.io/artio/rotation-acceptance@sha256:" + "a".repeat(64);
        var manifest = new PluginManifest("2", PLUGIN_ID, "Rotation acceptance", "Artio", "1.0.0", "Test fixture",
                "0.1.0", "9.0.0", image, List.of(), List.of("storage.object.write"), fields, List.of(),
                "/health", "/diagnostics", PluginManifest.GeographicAvailability.global(), List.of(), List.of(), Map.of(),
                Map.of("/run/secrets/root", "managedRootUser", "/run/secrets/access", "runtimeAccessKey",
                        "/run/secrets/secret", "runtimeSecretKey"));
        String manifestJson = mapper.writeValueAsString(manifest);
        jdbc.update("INSERT INTO extension_plugins(plugin_id,manifest,image_reference,lifecycle_state) VALUES (?,?::jsonb,?,'CONFIGURED')",
                PLUGIN_ID, manifestJson, image);
        jdbc.update("INSERT INTO extension_plugin_releases(plugin_id,version,image_reference,manifest) VALUES (?,?,?,?::jsonb)",
                PLUGIN_ID, manifest.version(), image, manifestJson);
        Long releaseId = jdbc.queryForObject("SELECT release_id FROM extension_plugin_releases WHERE plugin_id=? AND version=?",
                Long.class, PLUGIN_ID, manifest.version());
        UUID instanceId = UUID.randomUUID();
        Map<String, String> encryptedSecrets = Map.of("managedRootUser", encryption.encrypt("oldrootuser123456789"),
                "runtimeAccessKey", encryption.encrypt("oldaccesskey12345678"),
                "runtimeSecretKey", encryption.encrypt("oldruntime123456789012345678901234567890"));
        jdbc.update("INSERT INTO extension_plugin_instances(instance_id,plugin_id,instance_key,release_id,compatibility_mode,"
                        + "lifecycle_state,runtime_identity,public_configuration,encrypted_secrets) "
                        + "VALUES (?,?,?,?,'CAPABILITY_V2','DISABLED',?,?::jsonb,?::jsonb)",
                instanceId, PLUGIN_ID, "test-instance", releaseId, "instance-" + instanceId.toString().replace("-", ""),
                "{\"mode\":\"managed\"}", mapper.writeValueAsString(encryptedSecrets));
        return instanceId;
    }

    private String decryptSecret(UUID instanceId, String field) {
        String ciphertext = jdbc.queryForObject("SELECT encrypted_secrets->>? FROM extension_plugin_instances WHERE instance_id=?",
                String.class, field, instanceId);
        return encryption.decrypt(ciphertext);
    }

    private ResponseEntity<Map> adminExchange(UUID instanceId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        return http.exchange(rotationPath(instanceId), HttpMethod.POST, new HttpEntity<>(headers), Map.class);
    }

    private String rotationPath(UUID instanceId) {
        return "/api/v1/admin/extensions/" + PLUGIN_ID + "/instances/" + instanceId + "/configuration/rotation/runtime";
    }
}
