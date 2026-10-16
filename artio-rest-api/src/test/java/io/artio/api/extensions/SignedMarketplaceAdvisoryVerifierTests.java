package io.artio.api.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.extensions.ReleaseDescriptorCanonicalizer;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SignedMarketplaceAdvisoryVerifierTests {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void verifiesSignedSnapshotAndRejectsTamperingAndExpiredSnapshot() throws Exception {
    var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    var verifier = new SignedMarketplaceAdvisoryVerifier(trustedKey(keys.getPublic().getEncoded()));
    Instant now = Instant.parse("2026-10-16T12:00:00Z");
    String envelope = signedEnvelope(keys, now.minusSeconds(60), now.plusSeconds(3600));

    var verified = verifier.verify(envelope.getBytes(java.nio.charset.StandardCharsets.UTF_8), now);

    assertThat(verified.sequence()).isEqualTo(1);
    assertThat(verified.advisories()).hasSize(1);
    assertThat(verified.advisories().getFirst().pluginId()).isEqualTo("shipping.demo");
    assertThatThrownBy(
            () ->
                verifier.verify(
                    envelope.replace("SUPPORTED", "UNSUPPORTED")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    now))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () ->
                verifier.verify(
                    signedEnvelope(keys, now.minusSeconds(8000), now.minusSeconds(1))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    now))
        .isInstanceOf(ResponseStatusException.class);
  }

  private static String signedEnvelope(
      java.security.KeyPair keys, Instant issuedAt, Instant expiresAt) throws Exception {
    var payload = MAPPER.createObjectNode();
    payload.put("schemaVersion", 1);
    payload.put("sequence", 1);
    payload.put("issuedAt", issuedAt.toString());
    payload.put("expiresAt", expiresAt.toString());
    payload.put("keyId", "test-root");
    var advisories = payload.putArray("advisories");
    var advisory = advisories.addObject();
    advisory.put("advisoryId", "a16974a8-e9ad-405a-8418-a0e1dd23c135");
    advisory.put("pluginId", "shipping.demo");
    advisory.put("version", "1.2.3");
    advisory.put("releaseDescriptorDigest", "sha256:" + "a".repeat(64));
    advisory.putArray("imageDigests").add("sha256:" + "b".repeat(64));
    advisory.putArray("bannedImageDigests");
    advisory.put("supportStatus", "SUPPORTED");
    advisory.put("securityStatus", "ALLOWED");
    advisory.put("status", "SUPPORTED");
    advisory.put("scope", "RELEASE");
    advisory.put("severity", "INFO");
    advisory.put("reasonCode", "SUPPORTED_RELEASE");
    advisory.putObject("explanations").put("en", "Release is supported.");
    advisory.put("issuedAt", issuedAt.toString());
    String payloadJson = MAPPER.writeValueAsString(payload);
    Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(keys.getPrivate());
    signer.update(ReleaseDescriptorCanonicalizer.canonicalBytes(payloadJson));
    var envelope = MAPPER.createObjectNode();
    envelope.set("payload", payload);
    envelope.putObject("signature")
        .put("algorithm", "Ed25519")
        .put("keyId", "test-root")
        .put("value", Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
    return MAPPER.writeValueAsString(envelope);
  }

  private static String trustedKey(byte[] encoded) {
    return "test-root=" + Base64.getUrlEncoder().withoutPadding().encodeToString(encoded);
  }
}
