package io.artio.api.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.extensions.ReleaseDescriptorCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OfficialReleaseDescriptorVerifierTests {
  private static final String IMAGE =
      "registry.example/public/plugins/shipping-demo@sha256:"
          + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

  @Test
  void verifiesCanonicalDescriptorWithAnExplicitTrustedKey() throws Exception {
    var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    String descriptorJson = descriptorJson();
    byte[] bytes = ReleaseDescriptorCanonicalizer.canonicalBytes(descriptorJson);
    Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(keys.getPrivate());
    signer.update(bytes);
    String envelope =
        "{\"descriptor\":"
            + new ObjectMapper().readTree(descriptorJson)
            + ",\"signature\":{\"algorithm\":\"Ed25519\",\"keyId\":\"test-root\",\"value\":\""
            + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())
            + "\"}}";
    var verifier = new OfficialReleaseDescriptorVerifier(trustedKey(keys.getPublic().getEncoded()));

    assertThat(
            verifier
                .verify(
                    envelope.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "shipping.demo",
                    "1.2.3")
                .path("pluginId")
                .asText())
        .isEqualTo("shipping.demo");
  }

  @Test
  void keyRotationAcceptsTheOverlapAndRejectsTheRetiredReleaseKey() throws Exception {
    var oldKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    var newKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    String descriptor = descriptorJson();
    String oldEnvelope = signedEnvelope(descriptor, "release-old", oldKey);
    String newEnvelope = signedEnvelope(descriptor, "release-new", newKey);
    String overlappingTrust =
        trustedKey("release-old", oldKey.getPublic().getEncoded())
            + ","
            + trustedKey("release-new", newKey.getPublic().getEncoded());

    var overlapVerifier = new OfficialReleaseDescriptorVerifier(overlappingTrust);
    assertThat(
            verify(overlapVerifier, oldEnvelope, "shipping.demo", "1.2.3")
                .path("version")
                .asText())
        .isEqualTo("1.2.3");
    assertThat(
            verify(overlapVerifier, newEnvelope, "shipping.demo", "1.2.3")
                .path("version")
                .asText())
        .isEqualTo("1.2.3");

    var afterRetirement =
        new OfficialReleaseDescriptorVerifier(
            trustedKey("release-new", newKey.getPublic().getEncoded()));
    assertThat(
            verify(afterRetirement, newEnvelope, "shipping.demo", "1.2.3")
                .path("pluginId")
                .asText())
        .isEqualTo("shipping.demo");
    assertThatThrownBy(() -> verify(afterRetirement, oldEnvelope, "shipping.demo", "1.2.3"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("signer is not trusted");
  }

  @Test
  void rejectsChangedIdentityTamperingUnknownSignerAndDuplicateFields() throws Exception {
    var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    var verifier = new OfficialReleaseDescriptorVerifier(trustedKey(keys.getPublic().getEncoded()));
    String descriptorJson = descriptorJson();
    String unsigned =
        "{\"descriptor\":"
            + descriptorJson
            + ",\"signature\":{\"algorithm\":\"Ed25519\",\"keyId\":\"unknown\",\"value\":\"AA\"}}";

    assertThatThrownBy(() -> verify(verifier, unsigned, "other.plugin", "1.2.3"))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> verify(verifier, unsigned, "shipping.demo", "1.2.3"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("not trusted");
    assertThatThrownBy(
            () ->
                verify(
                    verifier,
                    "{\"descriptor\":{},\"descriptor\":{},\"signature\":{}}",
                    "shipping.demo",
                    "1.2.3"))
        .isInstanceOf(ResponseStatusException.class);

    Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(keys.getPrivate());
    signer.update(ReleaseDescriptorCanonicalizer.canonicalBytes(descriptorJson));
    String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    String tampered =
        unsigned
            .replace("shipping-demo@sha256", "other-plugin@sha256")
            .replace("unknown", "test-root")
            .replace("AA", signature);
    assertThatThrownBy(() -> verify(verifier, tampered, "shipping.demo", "1.2.3"))
        .isInstanceOf(ResponseStatusException.class);
  }

  private static String trustedKey(byte[] encoded) {
    return trustedKey("test-root", encoded);
  }

  private static String trustedKey(String keyId, byte[] encoded) {
    return keyId + "=" + Base64.getUrlEncoder().withoutPadding().encodeToString(encoded);
  }

  private static JsonNode verify(
      OfficialReleaseDescriptorVerifier verifier, String envelope, String id, String version) {
    return verifier.verify(envelope.getBytes(StandardCharsets.UTF_8), id, version);
  }

  private static String signedEnvelope(String descriptorJson, String keyId, KeyPair keys)
      throws Exception {
    Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(keys.getPrivate());
    signer.update(ReleaseDescriptorCanonicalizer.canonicalBytes(descriptorJson));
    return "{\"descriptor\":"
        + new ObjectMapper().readTree(descriptorJson)
        + ",\"signature\":{\"algorithm\":\"Ed25519\",\"keyId\":\""
        + keyId
        + "\",\"value\":\""
        + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())
        + "\"}}";
  }

  private static String descriptorJson() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    var manifest = mapper.createObjectNode();
    manifest.put("id", "shipping.demo");
    manifest.put("version", "1.2.3");
    manifest.put("image", IMAGE);
    var descriptor = mapper.createObjectNode();
    descriptor.put("schemaVersion", 1);
    descriptor.put("pluginId", "shipping.demo");
    descriptor.put("version", "1.2.3");
    descriptor.put(
        "manifestDigest",
        "sha256:"
            + java.util.HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(
                            ReleaseDescriptorCanonicalizer.canonicalBytes(
                                mapper.writeValueAsString(manifest)))));
    descriptor.set("manifest", manifest);
    var images = descriptor.putArray("images");
    var image = images.addObject();
    image.put("role", "MAIN");
    image.put("repository", IMAGE.substring(0, IMAGE.lastIndexOf("@")));
    image.put("digest", IMAGE.substring(IMAGE.lastIndexOf("@") + 1));
    return mapper.writeValueAsString(descriptor);
  }
}
