package io.artio.api.extensions;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.extensions.ReleaseDescriptorCanonicalizer;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Verifies Marketplace's immutable Ed25519 release envelope using operator-configured roots. */
@Component
public final class OfficialReleaseDescriptorVerifier {
  private static final Set<String> DESCRIPTOR_FIELDS =
      Set.of("schemaVersion", "pluginId", "version", "manifestDigest", "manifest", "images");
  private static final Set<String> ENVELOPE_FIELDS = Set.of("descriptor", "signature");
  private static final Set<String> SIGNATURE_FIELDS = Set.of("algorithm", "keyId", "value");
  private static final Set<String> IMAGE_FIELDS = Set.of("role", "repository", "digest");

  private final ObjectMapper strictMapper =
      new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
  private final Map<String, String> trustedKeys;

  public OfficialReleaseDescriptorVerifier(
      @org.springframework.beans.factory.annotation.Value("${artio.marketplace.trusted-keys:}")
          String trustedKeys) {
    this.trustedKeys = parseTrustedKeys(trustedKeys);
  }

  public JsonNode verify(byte[] envelopeBytes, String expectedPluginId, String expectedVersion) {
    try {
      JsonNode envelope = strictMapper.readTree(envelopeBytes);
      requireFields(envelope, ENVELOPE_FIELDS, "signed release envelope");
      JsonNode descriptor = envelope.path("descriptor");
      requireFields(descriptor, DESCRIPTOR_FIELDS, "release descriptor");
      if (descriptor.path("schemaVersion").asInt(-1) != 1
          || !expectedPluginId.equals(descriptor.path("pluginId").asText())
          || !expectedVersion.equals(descriptor.path("version").asText())) {
        throw rejected("Release descriptor identity does not match the requested release");
      }
      String manifestDigest = descriptor.path("manifestDigest").asText();
      if (!manifestDigest.matches("sha256:[a-f0-9]{64}"))
        throw rejected("Release descriptor manifest digest is invalid");
      JsonNode manifest = descriptor.path("manifest");
      if (!manifest.isObject()) throw rejected("Release descriptor manifest is invalid");
      String manifestId = manifest.has("packageManifest")
          ? manifest.path("packageManifest").path("id").asText()
          : manifest.path("id").asText();
      String manifestVersion = manifest.has("packageManifest")
          ? manifest.path("packageManifest").path("version").asText()
          : manifest.path("version").asText();
      if (!expectedPluginId.equals(manifestId) || !expectedVersion.equals(manifestVersion))
        throw rejected("Signed manifest identity does not match the descriptor");
      String actualManifestDigest = "sha256:" + HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(ReleaseDescriptorCanonicalizer.canonicalBytes(strictMapper.writeValueAsString(manifest))));
      if (!MessageDigest.isEqual(manifestDigest.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          actualManifestDigest.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
        throw rejected("Signed manifest digest does not match the manifest bytes");
      JsonNode images = descriptor.path("images");
      if (!images.isArray() || images.isEmpty() || images.size() > 33)
        throw rejected("Release descriptor image inventory is invalid");
      Map<String, String> actualImages = new HashMap<>();
      for (JsonNode image : images) {
        requireFields(image, IMAGE_FIELDS, "release image");
        String role = image.path("role").asText();
        String digest = image.path("digest").asText();
        String repository = image.path("repository").asText();
        if (!("MAIN".equals(role) || role.matches("SIDECAR:[a-z0-9][a-z0-9._-]{0,63}"))
            || !digest.matches("sha256:[a-f0-9]{64}")
            || !repository.matches("[A-Za-z0-9.-]+(?::[0-9]{1,5})?/public/plugins/[A-Za-z0-9._/-]+")
            || actualImages.putIfAbsent(role, repository + "@" + digest) != null)
          throw rejected("Release descriptor contains an invalid image reference");
      }
      Map<String, String> manifestImages = new HashMap<>();
      JsonNode runtimeManifest =
          manifest.has("packageManifest") ? manifest.path("packageManifest") : manifest;
      addManifestImage(manifestImages, "MAIN", runtimeManifest.path("image").asText());
      JsonNode services = runtimeManifest.path("services");
      if (services.isArray()) for (JsonNode service : services) {
        String id = service.path("id").asText();
        if (!id.matches("[a-z0-9][a-z0-9._-]{0,63}")) throw rejected("Manifest sidecar id is invalid");
        addManifestImage(manifestImages, "SIDECAR:" + id, service.path("image").asText());
      }
      if (!actualImages.equals(manifestImages))
        throw rejected("Release descriptor image inventory does not match its manifest");

      JsonNode signature = envelope.path("signature");
      requireFields(signature, SIGNATURE_FIELDS, "release signature");
      if (!"Ed25519".equals(signature.path("algorithm").asText()))
        throw rejected("Release descriptor signature algorithm is unsupported");
      String keyId = signature.path("keyId").asText();
      String encodedKey = trustedKeys.get(keyId);
      if (encodedKey == null) throw rejected("Release descriptor signer is not trusted");
      byte[] publicKey = Base64.getUrlDecoder().decode(encodedKey);
      byte[] signatureBytes = Base64.getUrlDecoder().decode(signature.path("value").asText());
      byte[] canonical = ReleaseDescriptorCanonicalizer.canonicalBytes(strictMapper.writeValueAsString(descriptor));
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(publicKey)));
      verifier.update(canonical);
      if (!verifier.verify(signatureBytes)) throw rejected("Release descriptor signature is invalid");
      return descriptor.deepCopy();
    } catch (ResponseStatusException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Marketplace release signature could not be verified", failure);
    }
  }

  private static void requireFields(JsonNode node, Set<String> expected, String label) {
    if (node == null || !node.isObject() || node.size() != expected.size())
      throw rejected("Invalid " + label + " shape");
    var names = node.fieldNames();
    while (names.hasNext()) if (!expected.contains(names.next())) throw rejected("Unknown " + label + " field");
    if (!node.fieldNames().hasNext()) throw rejected("Empty " + label);
  }

  private static ResponseStatusException rejected(String message) {
    return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
  }

  private static void addManifestImage(Map<String, String> inventory, String role, String reference) {
    int separator = reference.lastIndexOf("@sha256:");
    if (separator <= 0) throw rejected("Manifest image is not pinned by a sha256 digest");
    String repository = reference.substring(0, separator);
    String digest = reference.substring(separator + 1);
    if (!digest.matches("sha256:[a-f0-9]{64}")
        || !repository.matches("[A-Za-z0-9.-]+(?::[0-9]{1,5})?/public/plugins/[A-Za-z0-9._/-]+")
        || inventory.putIfAbsent(role, repository + "@" + digest) != null)
      throw rejected("Manifest image inventory is invalid");
  }

  static Map<String, String> parseTrustedKeys(String configured) {
    if (configured == null || configured.isBlank()) return Map.of();
    var keys = new java.util.LinkedHashMap<String, String>();
    for (String entry : configured.split(",")) {
      String[] pair = entry.trim().split("=", 2);
      if (pair.length != 2 || !pair[0].matches("[A-Za-z0-9._-]{1,120}") || pair[1].isBlank()
          || keys.putIfAbsent(pair[0], pair[1]) != null)
        throw new IllegalArgumentException("artio.marketplace.trusted-keys must be unique keyId=base64url entries");
      try { Base64.getUrlDecoder().decode(pair[1]); }
      catch (IllegalArgumentException failure) { throw new IllegalArgumentException("Marketplace trusted key must use base64url", failure); }
    }
    return Map.copyOf(keys);
  }
}
