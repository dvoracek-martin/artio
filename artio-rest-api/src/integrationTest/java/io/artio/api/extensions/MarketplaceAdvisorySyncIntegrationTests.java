package io.artio.api.extensions;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.artio.extensions.ReleaseDescriptorCanonicalizer;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises Core's outbound HTTP fetch, independent signature verification, and durable policy write. */
@Testcontainers(disabledWithoutDocker = true)
class MarketplaceAdvisorySyncIntegrationTests {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void fetchesVerifiesAndPersistsSignedMarketplaceFeedOverHttp() throws Exception {
    KeyPair signingKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    Instant now = Instant.now();
    String pluginId = "security.sync." + UUID.randomUUID().toString().replace("-", "");
    String digest = "sha256:" + "c".repeat(64);
    byte[] envelope = signedEnvelope(signingKey, pluginId, digest, now.minusSeconds(1), now.plusSeconds(3600));
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/v1/marketplace/security/advisories", exchange -> {
      if (!"GET".equals(exchange.getRequestMethod())) {
        exchange.sendResponseHeaders(405, -1);
        exchange.close();
        return;
      }
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, envelope.length);
      try (var body = exchange.getResponseBody()) {
        body.write(envelope);
      }
    });
    server.start();
    try {
      Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
          .locations("classpath:db/migration").load().migrate();
      JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
          POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
      String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
      String trustedKeys = "test-marketplace-root=" + Base64.getUrlEncoder().withoutPadding()
          .encodeToString(signingKey.getPublic().getEncoded());
      var marketplace = new MarketplaceCatalogClient(MAPPER, baseUrl, new OfficialReleaseDescriptorVerifier(""));
      var verifier = new SignedMarketplaceAdvisoryVerifier(trustedKeys);
      var store = new MarketplaceAdvisoryStore(jdbc, MAPPER);

      new MarketplaceAdvisorySyncWorker(marketplace, verifier, store).syncSafely();

      assertThat(store.status().sequence()).isEqualTo(1);
      assertThat(store.status().lastErrorCode()).isNull();
      assertThat(jdbc.queryForObject("SELECT security_status FROM extension_marketplace_release_advisories WHERE plugin_id=?",
          String.class, pluginId)).isEqualTo("BANNED");
      assertThat(jdbc.queryForObject("SELECT count(*) FROM extension_marketplace_banned_artifacts WHERE image_digest=?",
          Integer.class, digest)).isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM extension_marketplace_advisory_sync_audit WHERE outcome='APPLIED'",
          Integer.class)).isEqualTo(1);
    } finally {
      server.stop(0);
    }
  }

  private static byte[] signedEnvelope(KeyPair keyPair, String pluginId, String imageDigest,
      Instant issuedAt, Instant expiresAt) throws Exception {
    var payload = MAPPER.createObjectNode();
    payload.put("schemaVersion", 1);
    payload.put("sequence", 1);
    payload.put("issuedAt", issuedAt.toString());
    payload.put("expiresAt", expiresAt.toString());
    payload.put("keyId", "test-marketplace-root");
    var advisory = payload.putArray("advisories").addObject();
    advisory.put("advisoryId", UUID.randomUUID().toString());
    advisory.put("pluginId", pluginId);
    advisory.put("version", "1.0.0");
    advisory.put("releaseDescriptorDigest", "sha256:" + "d".repeat(64));
    advisory.putArray("imageDigests").add(imageDigest);
    advisory.putArray("bannedImageDigests").add(imageDigest);
    advisory.put("supportStatus", "SUPPORTED");
    advisory.put("securityStatus", "BANNED");
    advisory.put("status", "BANNED");
    advisory.put("scope", "RELEASE");
    advisory.put("severity", "CRITICAL");
    advisory.put("reasonCode", "SECURITY_REVOKED");
    advisory.putObject("explanations").put("en", "This test release is revoked.");
    advisory.put("issuedAt", issuedAt.toString());

    Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(keyPair.getPrivate());
    signer.update(ReleaseDescriptorCanonicalizer.canonicalBytes(MAPPER.writeValueAsString(payload)));
    var envelope = MAPPER.createObjectNode();
    envelope.set("payload", payload);
    envelope.putObject("signature")
        .put("algorithm", "Ed25519")
        .put("keyId", "test-marketplace-root")
        .put("value", Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
    return MAPPER.writeValueAsBytes(envelope);
  }
}
