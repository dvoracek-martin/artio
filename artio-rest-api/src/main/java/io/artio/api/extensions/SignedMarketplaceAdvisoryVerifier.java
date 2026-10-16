package io.artio.api.extensions;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.extensions.ReleaseDescriptorCanonicalizer;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Independently verifies the Marketplace advisory signing root and complete snapshot contract. */
@Component
public final class SignedMarketplaceAdvisoryVerifier {
  private static final Set<String> ENVELOPE_FIELDS = Set.of("payload", "signature");
  private static final Set<String> SIGNATURE_FIELDS = Set.of("algorithm", "keyId", "value");
  private static final Set<String> PAYLOAD_FIELDS =
      Set.of("schemaVersion", "sequence", "issuedAt", "expiresAt", "keyId", "advisories");
  private static final Set<String> ADVISORY_FIELDS =
      Set.of(
          "advisoryId",
          "pluginId",
          "version",
          "releaseDescriptorDigest",
          "imageDigests",
          "bannedImageDigests",
          "supportStatus",
          "securityStatus",
          "status",
          "scope",
          "severity",
          "reasonCode",
          "explanations",
          "issuedAt");
  private static final Set<String> LOCALES = Set.of("en", "de", "fr", "it");
  private static final TypeReference<Map<String, String>> TEXT_MAP = new TypeReference<>() {};
  private final ObjectMapper strictMapper =
      new ObjectMapper(
          JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
  private final Map<String, String> trustedKeys;

  public SignedMarketplaceAdvisoryVerifier(
      @Value("${artio.marketplace.advisories.trusted-keys:}") String configuredKeys) {
    this.trustedKeys = OfficialReleaseDescriptorVerifier.parseTrustedKeys(configuredKeys);
  }

  public VerifiedSnapshot verify(byte[] envelopeBytes, Instant now) {
    try {
      JsonNode envelope = strictMapper.readTree(envelopeBytes);
      requireFields(envelope, ENVELOPE_FIELDS, "advisory envelope");
      JsonNode payload = envelope.path("payload");
      JsonNode signatureBlock = envelope.path("signature");
      requireFields(payload, PAYLOAD_FIELDS, "advisory payload");
      requireFields(signatureBlock, SIGNATURE_FIELDS, "advisory signature");
      String keyId = requiredText(payload, "keyId", "payload key ID");
      if (!keyId.matches("[A-Za-z0-9._-]{1,120}")
          || !keyId.equals(requiredText(signatureBlock, "keyId", "signature key ID"))
          || !"Ed25519".equals(requiredText(signatureBlock, "algorithm", "signature algorithm"))) {
        throw rejected("Marketplace advisory signer identity is invalid");
      }
      String trustedKey = trustedKeys.get(keyId);
      if (trustedKey == null) throw rejected("Marketplace advisory signer is not trusted");
      byte[] publicKey = Base64.getUrlDecoder().decode(trustedKey);
      byte[] signature = Base64.getUrlDecoder().decode(requiredText(signatureBlock, "value", "signature"));
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(
          KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(publicKey)));
      verifier.update(
          ReleaseDescriptorCanonicalizer.canonicalBytes(
              strictMapper.writeValueAsString(payload)));
      if (!verifier.verify(signature)) throw rejected("Marketplace advisory signature is invalid");

      if (payload.path("schemaVersion").asInt(-1) != 1 || !payload.path("sequence").isIntegralNumber())
        throw rejected("Marketplace advisory schema version or sequence is invalid");
      long sequence = payload.path("sequence").asLong(-1);
      if (sequence < 1) throw rejected("Marketplace advisory sequence must be positive");
      Instant issuedAt = instant(payload, "issuedAt");
      Instant expiresAt = instant(payload, "expiresAt");
      if (issuedAt.isAfter(now.plus(Duration.ofMinutes(5)))
          || !expiresAt.isAfter(issuedAt)
          || Duration.between(issuedAt, expiresAt).compareTo(Duration.ofDays(7)) > 0
          || !expiresAt.isAfter(now)) {
        throw rejected("Marketplace advisory snapshot is expired or has an invalid time window");
      }
      JsonNode advisoryArray = payload.path("advisories");
      if (!advisoryArray.isArray() || advisoryArray.size() > 10_000)
        throw rejected("Marketplace advisory list exceeds its bounds");
      List<Advisory> advisories = new ArrayList<>(advisoryArray.size());
      Set<String> identities = new HashSet<>();
      Set<String> advisoryIds = new HashSet<>();
      for (JsonNode advisory : advisoryArray) {
        Advisory parsed = parseAdvisory(advisory, sequence);
        if (!identities.add(parsed.pluginId() + "\n" + parsed.version())
            || !advisoryIds.add(parsed.advisoryId().toString())) {
          throw rejected("Marketplace advisory list contains duplicate identities");
        }
        advisories.add(parsed);
      }
      byte[] payloadDigest =
          MessageDigest.getInstance("SHA-256")
              .digest(ReleaseDescriptorCanonicalizer.canonicalBytes(strictMapper.writeValueAsString(payload)));
      return new VerifiedSnapshot(
          sequence, issuedAt, expiresAt, HexFormat.of().formatHex(payloadDigest), advisories);
    } catch (ResponseStatusException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "Marketplace advisory snapshot could not be verified", failure);
    }
  }

  private Advisory parseAdvisory(JsonNode node, long sequence) {
    requireFields(node, ADVISORY_FIELDS, "advisory entry");
    UUID advisoryId;
    try {
      advisoryId = UUID.fromString(requiredText(node, "advisoryId", "advisory ID"));
    } catch (IllegalArgumentException invalid) {
      throw rejected("Marketplace advisory ID is invalid");
    }
    String pluginId = requiredText(node, "pluginId", "plugin ID");
    String version = requiredText(node, "version", "version");
    if (!pluginId.matches("[a-z0-9][a-z0-9._-]{0,159}")
        || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?")) {
      throw rejected("Marketplace advisory release identity is invalid");
    }
    String releaseDigest = requiredText(node, "releaseDescriptorDigest", "descriptor digest");
    if (!releaseDigest.matches("sha256:[a-f0-9]{64}"))
      throw rejected("Marketplace advisory descriptor digest is invalid");
    List<String> imageDigests = digests(node.path("imageDigests"), true, "image digests");
    List<String> bannedDigests = digests(node.path("bannedImageDigests"), false, "banned image digests");
    if (!imageDigests.containsAll(bannedDigests))
      throw rejected("Marketplace advisory bans an image outside the signed release inventory");
    String support = requiredText(node, "supportStatus", "support status");
    String security = requiredText(node, "securityStatus", "security status");
    String status = requiredText(node, "status", "combined status");
    if (!Set.of("SUPPORTED", "UNSUPPORTED", "REMOVED").contains(support)
        || !Set.of("ALLOWED", "BANNED").contains(security)
        || !status.equals("BANNED".equals(security) ? "BANNED" : support)
        || ("BANNED".equals(security) != !bannedDigests.isEmpty())
        || !"RELEASE".equals(requiredText(node, "scope", "scope"))) {
      throw rejected("Marketplace advisory support/security status is inconsistent");
    }
    String severity = requiredText(node, "severity", "severity");
    String reasonCode = requiredText(node, "reasonCode", "reason code");
    if (!Set.of("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL").contains(severity)
        || !reasonCode.matches("[A-Z][A-Z0-9_]{0,47}")) {
      throw rejected("Marketplace advisory reason or severity is invalid");
    }
    JsonNode explanationsNode = node.path("explanations");
    if (!explanationsNode.isObject()
        || !explanationsNode.has("en")
        || explanationsNode.size() > LOCALES.size()) {
      throw rejected("Marketplace advisory explanations are invalid");
    }
    for (String locale : toNames(explanationsNode)) {
      if (!LOCALES.contains(locale)) throw rejected("Marketplace advisory locale is unsupported");
      String value = explanationsNode.path(locale).asText(null);
      if (value == null || value.isBlank() || value.length() > 500 || value.contains("<") || value.contains(">"))
        throw rejected("Marketplace advisory explanation is not bounded plain text");
    }
    Map<String, String> explanations = strictMapper.convertValue(explanationsNode, TEXT_MAP);
    return new Advisory(
        advisoryId,
        pluginId,
        version,
        releaseDigest,
        imageDigests,
        bannedDigests,
        support,
        security,
        status,
        severity,
        reasonCode,
        explanations,
        instant(node, "issuedAt"),
        sequence);
  }

  private static List<String> digests(JsonNode node, boolean required, String label) {
    if (!node.isArray() || (required && node.isEmpty()) || node.size() > 32)
      throw rejected("Marketplace advisory " + label + " are invalid");
    List<String> values = new ArrayList<>(node.size());
    Set<String> unique = new HashSet<>();
    for (JsonNode value : node) {
      String digest = value.asText(null);
      if (digest == null || !digest.matches("sha256:[a-f0-9]{64}") || !unique.add(digest))
        throw rejected("Marketplace advisory " + label + " contain an invalid or duplicate digest");
      values.add(digest);
    }
    return List.copyOf(values);
  }

  private static Instant instant(JsonNode node, String field) {
    try {
      return Instant.parse(requiredText(node, field, field));
    } catch (Exception invalid) {
      throw rejected("Marketplace advisory " + field + " timestamp is invalid");
    }
  }

  private static String requiredText(JsonNode node, String field, String label) {
    String value = node.path(field).asText(null);
    if (value == null || value.isBlank()) throw rejected("Marketplace advisory " + label + " is missing");
    return value;
  }

  private static List<String> toNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static void requireFields(JsonNode node, Set<String> expected, String label) {
    if (node == null || !node.isObject() || node.size() != expected.size())
      throw rejected("Marketplace " + label + " has an invalid shape");
    for (String name : toNames(node)) if (!expected.contains(name))
      throw rejected("Marketplace " + label + " contains an unknown field");
  }

  private static ResponseStatusException rejected(String message) {
    return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
  }

  public record VerifiedSnapshot(
      long sequence,
      Instant issuedAt,
      Instant expiresAt,
      String payloadSha256,
      List<Advisory> advisories) {
    public VerifiedSnapshot {
      advisories = List.copyOf(advisories);
    }
  }

  public record Advisory(
      UUID advisoryId,
      String pluginId,
      String version,
      String releaseDescriptorDigest,
      List<String> imageDigests,
      List<String> bannedImageDigests,
      String supportStatus,
      String securityStatus,
      String status,
      String severity,
      String reasonCode,
      Map<String, String> explanations,
      Instant issuedAt,
      long sequence) {
    public Advisory {
      imageDigests = List.copyOf(imageDigests);
      bannedImageDigests = List.copyOf(bannedImageDigests);
      explanations = Map.copyOf(explanations);
    }
  }
}
