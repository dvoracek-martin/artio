package io.artio.api.licensing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = io.artio.api.ArtioRestApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LicenseApiIntegrationTests {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final KeyPair ISSUER;
    private static final Path PUBLIC_KEYS;
    static {
        try {
            ISSUER = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            PUBLIC_KEYS = Files.createTempFile("artio-license-api-public-keys-", ".json");
            PUBLIC_KEYS.toFile().deleteOnExit();
            Files.writeString(PUBLIC_KEYS, new ObjectMapper().writeValueAsString(Map.of("fixture",
                    Base64.getEncoder().encodeToString(ISSUER.getPublic().getEncoded()))));
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder jwt;
    @Autowired ObjectMapper mapper;
    @Autowired LicensingService licensing;
    @LocalServerPort int port;
    private UUID owner;
    private String ownerToken;
    private String adminToken;
    private String customerToken;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("artio.security.jwt-secret", () -> "license-api-integration-jwt-key-at-least-32-bytes");
        registry.add("artio.security.encryption-key", () -> "license-api-integration-aes-key-at-least-32-bytes");
        registry.add("artio.setup.token", () -> "license-api-integration-setup-token-at-least-32-bytes");
        registry.add("artio.licensing.public-keys-file", PUBLIC_KEYS::toString);
        registry.add("artio.workers.enabled", () -> "false");
        registry.add("artio.notifications.worker.enabled", () -> "false");
        registry.add("artio.events.kafka.enabled", () -> "false");
        registry.add("artio.events.kafka.consumer-enabled", () -> "false");
        registry.add("artio.extensions.docker.enabled", () -> "false");
    }

    @BeforeEach
    void authenticatedRolesAndCommunityState() {
        jdbc.update("UPDATE artio_license_state SET signed_document=NULL,revision=revision+1");
        licensing.reload();
        owner = user("SUPERADMIN");
        ownerToken = token(owner, "SUPERADMIN");
        adminToken = token(user("ADMIN"), "ADMIN");
        customerToken = token(user("CUSTOMER"), "CUSTOMER");
    }

    @Test
    void onlyOwnerCanReadInstallOrRefreshAndResponsesDoNotExposeSignedContents() throws Exception {
        String path = "/api/v1/admin/system/license";
        assertThat(http.getForEntity(path, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        for (String denied : List.of(adminToken, customerToken)) {
            assertThat(exchange(path, HttpMethod.GET, null, denied).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exchange(path, HttpMethod.POST, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), denied).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exchange(path + "/refresh", HttpMethod.POST, null, denied).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
        var initial = exchange(path, HttpMethod.GET, null, ownerToken);
        assertThat(initial.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(initial.getBody().path("edition").asText()).isEqualTo("COMMUNITY");
        UUID identity = UUID.fromString(initial.getBody().path("installationId").asText());
        byte[] signed = signed(identity);
        var installed = exchange(path, HttpMethod.POST, signed, ownerToken);
        assertThat(installed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(installed.getBody().path("edition").asText()).isEqualTo("ENTERPRISE");
        assertThat(installed.getBody().path("features").toString()).contains("LOYALTY");
        assertThat(installed.getBody().toString()).doesNotContain("payload", "signature", "licenseId", "keyId");
        assertThat(exchange(path, HttpMethod.POST, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), ownerToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(path, HttpMethod.POST, new String(signed, java.nio.charset.StandardCharsets.UTF_8)
                .getBytes(java.nio.charset.StandardCharsets.UTF_16LE), ownerToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(licensing.allows(EnterpriseFeature.LOYALTY)).isTrue();
        var refresh = exchange(path + "/refresh", HttpMethod.POST, null, ownerToken);
        assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refresh.getBody().path("refreshStatus").asText()).isEqualTo("NOT_CONFIGURED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE user_id=? AND action='LICENSE_CHECK'", Integer.class, owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE user_id=? AND action='LICENSE_INSTALL'", Integer.class, owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT signed_document FROM artio_license_state", String.class)).isEqualTo(new String(signed, java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void boundedOwnerUploadRejectsBothFixedAndChunkedBodiesWithoutChangingCommunity() throws Exception {
        byte[] oversized = new byte[65_537];
        assertThat(exchange("/api/v1/admin/system/license", HttpMethod.POST, oversized, ownerToken).getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/admin/system/license"))
                    .timeout(java.time.Duration.ofSeconds(5)).header("Authorization", "Bearer " + ownerToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(oversized))).build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(413);
        }
        assertThat(licensing.status().edition()).isEqualTo("COMMUNITY");
        assertThat(jdbc.queryForObject("SELECT signed_document FROM artio_license_state", String.class)).isNull();
    }

    private org.springframework.http.ResponseEntity<JsonNode> exchange(String path, HttpMethod method, byte[] body, String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token); headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private UUID user(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,role,created_at) VALUES (?,?,'fixture-hash','License','Fixture',?,now())",
                id, "license-" + id + "@example.test", role);
        return id;
    }

    private String token(UUID user, String role) {
        Instant now = Instant.now();
        return jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), JwtClaimsSet.builder()
                .subject(user.toString()).claim("role", role).issuedAt(now).expiresAt(now.plusSeconds(300)).build())).getTokenValue();
    }

    private byte[] signed(UUID identity) throws Exception {
        Instant now = Instant.now().minusSeconds(1);
        byte[] payload = mapper.writeValueAsBytes(Map.of("version", 1, "licenseId", UUID.randomUUID().toString(),
                "installationId", identity.toString(), "issuedAt", now.getEpochSecond(), "notBefore", now.getEpochSecond(),
                "expiresAt", now.plusSeconds(3600).getEpochSecond(), "graceUntil", now.plusSeconds(7200).getEpochSecond(), "features", List.of("LOYALTY")));
        Signature signature = Signature.getInstance("Ed25519"); signature.initSign(ISSUER.getPrivate()); signature.update(payload);
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return mapper.writeValueAsBytes(Map.of("keyId", "fixture", "payload", encoder.encodeToString(payload), "signature", encoder.encodeToString(signature.sign())));
    }
}
