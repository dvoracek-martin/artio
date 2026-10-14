package io.artio.api.licensing;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Bounded Ed25519 verification, independent from HTTP, Spring, persistence and issuer availability. */
public final class SignedEntitlementVerifier {
    public static final int MAX_DOCUMENT_BYTES = 65_536;
    private static final int MAX_PAYLOAD_BYTES = 16_384;
    private static final Duration MAX_GRACE = Duration.ofDays(7);
    private static final Set<String> ENVELOPE_FIELDS = Set.of("keyId", "payload", "signature");
    private static final Set<String> PAYLOAD_FIELDS = Set.of("version", "licenseId", "installationId", "issuedAt",
            "notBefore", "expiresAt", "graceUntil", "features");
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private final Map<String, PublicKey> trustedKeys;
    private final UUID installationId;

    public SignedEntitlementVerifier(Map<String, PublicKey> trustedKeys, UUID installationId) {
        this.trustedKeys = Map.copyOf(trustedKeys);
        this.installationId = java.util.Objects.requireNonNull(installationId);
    }

    public VerifiedEntitlement verify(byte[] document, Instant now) {
        try {
            if (document == null || document.length == 0 || document.length > MAX_DOCUMENT_BYTES) throw invalid();
            JsonNode envelope = parse(document);
            requireFields(envelope, ENVELOPE_FIELDS);
            String keyId = text(envelope, "keyId");
            if (!keyId.matches("[A-Za-z0-9._-]{1,64}")) throw invalid();
            PublicKey key = trustedKeys.get(keyId);
            if (key == null) throw invalid();
            byte[] payload = decode(text(envelope, "payload"), MAX_PAYLOAD_BYTES);
            byte[] signature = decode(text(envelope, "signature"), 64);
            if (signature.length != 64) throw invalid();
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(payload);
            if (!verifier.verify(signature)) throw invalid();

            JsonNode claims = parse(payload);
            requireFields(claims, PAYLOAD_FIELDS);
            if (integer(claims, "version") != 1) throw invalid();
            UUID licensedInstallation = uuid(claims, "installationId");
            if (!installationId.equals(licensedInstallation)) throw invalid();
            UUID license = uuid(claims, "licenseId");
            Instant issued = timestamp(claims, "issuedAt");
            Instant starts = timestamp(claims, "notBefore");
            Instant expires = timestamp(claims, "expiresAt");
            Instant grace = timestamp(claims, "graceUntil");
            if (issued.isAfter(now.plusSeconds(300)) || issued.isAfter(starts) || !starts.isBefore(expires)
                    || expires.isAfter(grace) || Duration.between(expires, grace).compareTo(MAX_GRACE) > 0) throw invalid();
            JsonNode features = claims.get("features");
            if (!features.isArray() || features.size() > EnterpriseFeature.values().length) throw invalid();
            Set<EnterpriseFeature> grants = new HashSet<>();
            for (JsonNode feature : features) {
                if (!feature.isTextual() || !grants.add(EnterpriseFeature.valueOf(feature.textValue()))) throw invalid();
            }
            return new VerifiedEntitlement(license, licensedInstallation, issued, starts, expires, grace, grants);
        } catch (Exception failure) {
            // Never retain untrusted document contents in diagnostics or exception causes.
            throw invalid();
        }
    }

    private JsonNode parse(byte[] bytes) throws Exception {
        try (var parser = mapper.createParser(bytes)) {
            JsonNode value = mapper.readTree(parser);
            if (parser.nextToken() != null) throw invalid();
            return value;
        }
    }

    private static void requireFields(JsonNode value, Set<String> expected) {
        if (value == null || !value.isObject()) throw invalid();
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw invalid();
    }

    private static String text(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isTextual() || node.textValue().isEmpty()) throw invalid();
        return node.textValue();
    }

    private static long integer(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) throw invalid();
        return node.longValue();
    }

    private static Instant timestamp(JsonNode value, String field) {
        long seconds = integer(value, field);
        if (seconds < 0) throw invalid();
        return Instant.ofEpochSecond(seconds);
    }

    private static UUID uuid(JsonNode value, String field) {
        String encoded = text(value, field);
        UUID id = UUID.fromString(encoded);
        if (!id.toString().equals(encoded)) throw invalid();
        return id;
    }

    private static byte[] decode(String encoded, int maxBytes) {
        if (encoded.length() > (maxBytes * 4L + 2) / 3 || !encoded.matches("[A-Za-z0-9_-]+")) throw invalid();
        byte[] bytes = Base64.getUrlDecoder().decode(encoded);
        if (bytes.length > maxBytes || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded)) throw invalid();
        return bytes;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid signed entitlement");
    }
}
