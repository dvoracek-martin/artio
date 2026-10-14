package io.artio.api.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SignedEntitlementVerifierTests {
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    private static final UUID INSTALLATION = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    // Ephemeral test-only issuer keys; no production signing material is shipped in Core.
    private final KeyPair issuer = keyPair();
    private final SignedEntitlementVerifier verifier = new SignedEntitlementVerifier(Map.of("issuer-v1", issuer.getPublic()), INSTALLATION);

    @Test
    void verifiesOnlyExplicitSignedFeaturesForThisInstallation() throws Exception {
        VerifiedEntitlement grant = verifier.verify(sign(claims()), NOW);
        assertThat(grant.allows(EnterpriseFeature.LOYALTY, NOW)).isTrue();
        assertThat(grant.allows(EnterpriseFeature.SUBSCRIPTIONS, NOW)).isFalse();
        assertThat(grant.installationId()).isEqualTo(INSTALLATION);
        assertThatThrownBy(() -> grant.features().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void observesExactValidityAndGraceBoundariesWithoutExtendingThem() throws Exception {
        var grant = verifier.verify(sign(claims()), NOW);
        assertThat(grant.stateAt(grant.notBefore().minusNanos(1))).isEqualTo(VerifiedEntitlement.State.NOT_YET_VALID);
        assertThat(grant.stateAt(grant.notBefore())).isEqualTo(VerifiedEntitlement.State.ACTIVE);
        assertThat(grant.stateAt(grant.expiresAt().minusNanos(1))).isEqualTo(VerifiedEntitlement.State.ACTIVE);
        assertThat(grant.stateAt(grant.expiresAt())).isEqualTo(VerifiedEntitlement.State.GRACE);
        assertThat(grant.allows(EnterpriseFeature.LOYALTY, grant.graceUntil().minusNanos(1))).isTrue();
        assertThat(grant.stateAt(grant.graceUntil())).isEqualTo(VerifiedEntitlement.State.EXPIRED);
        assertThat(grant.allows(EnterpriseFeature.LOYALTY, grant.graceUntil())).isFalse();
    }

    @Test
    void rejectsTamperingUnknownIssuerWrongInstallationAndUnsignedDocuments() throws Exception {
        var envelope = mapper.readTree(sign(claims()));
        var changedClaims = claims();
        changedClaims.put("features", List.of("LOYALTY", "SUBSCRIPTIONS"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) envelope).put("payload", encode(mapper.writeValueAsBytes(changedClaims)));
        invalid(mapper.writeValueAsBytes(envelope));
        ((com.fasterxml.jackson.databind.node.ObjectNode) envelope).put("keyId", "unknown");
        invalid(mapper.writeValueAsBytes(envelope));
        changedClaims = claims();
        changedClaims.put("installationId", UUID.randomUUID().toString());
        invalid(sign(changedClaims));
        invalid(mapper.writeValueAsBytes(claims()));
        invalid(sign(claims(), keyPair()));
    }

    @Test
    void rejectsUnknownDuplicateOrMistypedSignedFieldsAndFeatures() throws Exception {
        var bad = claims();
        bad.put("version", 2);
        invalid(sign(bad));
        bad = claims();
        bad.put("unrecognizedGrant", true);
        invalid(sign(bad));
        bad = claims();
        bad.remove("graceUntil");
        invalid(sign(bad));
        for (Object features : List.of(List.of("LOYALTY", "LOYALTY"), List.of("CORE_COMMERCE"), List.of(1), "LOYALTY")) {
            bad = claims();
            bad.put("features", features);
            invalid(sign(bad));
        }
        bad = claims();
        bad.put("issuedAt", "1792022400");
        invalid(sign(bad));
        String duplicate = mapper.writeValueAsString(claims()).replace("\"version\":1", "\"version\":1,\"version\":1");
        invalid(signPayload(duplicate.getBytes(StandardCharsets.UTF_8), issuer));
    }

    @Test
    void rejectsInvalidOrExcessiveSignedTimeWindows() throws Exception {
        for (Map<String, Object> changes : List.of(
                Map.<String, Object>of("issuedAt", NOW.plusSeconds(301).getEpochSecond(), "notBefore", NOW.plusSeconds(301).getEpochSecond()),
                Map.<String, Object>of("issuedAt", NOW.getEpochSecond() + 1),
                Map.<String, Object>of("expiresAt", NOW.getEpochSecond()),
                Map.<String, Object>of("graceUntil", NOW.getEpochSecond()),
                Map.<String, Object>of("graceUntil", NOW.plusSeconds(3600 + 7 * 86400 + 1).getEpochSecond()),
                Map.<String, Object>of("issuedAt", -1L),
                Map.<String, Object>of("expiresAt", Long.MAX_VALUE))) {
            var bad = claims();
            bad.putAll(changes);
            invalid(sign(bad));
        }
        var noGrace = claims();
        noGrace.put("graceUntil", noGrace.get("expiresAt"));
        var grant = verifier.verify(sign(noGrace), NOW);
        assertThat(grant.stateAt(grant.expiresAt())).isEqualTo(VerifiedEntitlement.State.EXPIRED);
    }

    @Test
    void rejectsMalformedNoncanonicalOversizedAndTrailingEnvelopes() throws Exception {
        invalid(null);
        invalid(new byte[SignedEntitlementVerifier.MAX_DOCUMENT_BYTES + 1]);
        invalid("{".getBytes(StandardCharsets.UTF_8));
        invalid((new String(sign(claims()), StandardCharsets.UTF_8) + " {}").getBytes(StandardCharsets.UTF_8));
        String duplicate = new String(sign(claims()), StandardCharsets.UTF_8)
                .replace("\"keyId\":\"issuer-v1\"", "\"keyId\":\"issuer-v1\",\"keyId\":\"issuer-v1\"");
        invalid(duplicate.getBytes(StandardCharsets.UTF_8));
        var envelope = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(sign(claims()));
        envelope.put("signature", envelope.path("signature").asText() + "==");
        invalid(mapper.writeValueAsBytes(envelope));
        envelope.put("signature", encode(new byte[63]));
        invalid(mapper.writeValueAsBytes(envelope));
        envelope.put("payload", encode(new byte[16_385]));
        invalid(mapper.writeValueAsBytes(envelope));
    }

    @Test
    void rejectsNonUtf8EnvelopesAndPayloadsThatWouldChangeWhenPersistedAsText() throws Exception {
        String envelope = new String(sign(claims()), StandardCharsets.UTF_8);
        invalid(envelope.getBytes(StandardCharsets.UTF_16LE));
        invalid(envelope.getBytes(StandardCharsets.UTF_16));
        invalid(signPayload(mapper.writeValueAsString(claims()).getBytes(StandardCharsets.UTF_16LE), issuer));
        invalid(new byte[] {(byte) 0xc3, (byte) 0x28});
    }

    @Test
    void communityIsDefaultAndFailedRefreshPreservesLastGoodOnlyUntilSignedDeadline() throws Exception {
        var policy = new EntitlementPolicy(verifier);
        assertThat(policy.current()).isEmpty();
        assertThat(policy.allows(EnterpriseFeature.LOYALTY, NOW)).isFalse();
        assertThatThrownBy(() -> policy.require(EnterpriseFeature.LOYALTY, NOW)).isInstanceOf(FeatureNotLicensedException.class);
        byte[] document = sign(claims());
        VerifiedEntitlement first = policy.accept(document, NOW);
        assertThatThrownBy(() -> policy.accept(new byte[] {1}, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(policy.current()).contains(first);
        policy.require(EnterpriseFeature.LOYALTY, NOW);
        assertThat(policy.allows(EnterpriseFeature.LOYALTY, first.graceUntil())).isFalse();
        // Restart/reloading the same file after expiry cannot create a new grace window.
        var restarted = new EntitlementPolicy(verifier);
        restarted.accept(document, first.graceUntil());
        assertThat(restarted.allows(EnterpriseFeature.LOYALTY, first.graceUntil())).isFalse();
    }

    @Test
    void acceptsSignedFeatureRemovalButRejectsReplayAndConflictingSameTimeReplacement() throws Exception {
        var policy = new EntitlementPolicy(verifier);
        byte[] original = sign(claims());
        var first = policy.accept(original, NOW);
        assertThat(policy.accept(original, NOW)).isEqualTo(first);
        var removed = claims();
        removed.put("issuedAt", NOW.plusSeconds(1).getEpochSecond());
        removed.put("notBefore", NOW.plusSeconds(1).getEpochSecond());
        removed.put("features", List.of());
        var second = policy.accept(sign(removed), NOW.plusSeconds(2));
        assertThat(policy.allows(EnterpriseFeature.LOYALTY, NOW.plusSeconds(2))).isFalse();
        assertThatThrownBy(() -> policy.accept(original, NOW.plusSeconds(2))).isInstanceOf(IllegalArgumentException.class);
        removed.put("features", List.of("SUBSCRIPTIONS"));
        byte[] conflicting = sign(removed);
        assertThatThrownBy(() -> policy.accept(conflicting, NOW.plusSeconds(2))).isInstanceOf(IllegalArgumentException.class);
        assertThat(policy.current()).contains(second);
    }

    @Test
    void futureReplacementCannotPrematurelyEraseAnActiveGrant() throws Exception {
        var policy = new EntitlementPolicy(verifier);
        var first = policy.accept(sign(claims()), NOW);
        var future = claims();
        future.put("notBefore", NOW.plusSeconds(1800).getEpochSecond());
        byte[] replacement = sign(future);
        assertThatThrownBy(() -> policy.accept(replacement, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(policy.current()).contains(first);
        assertThat(policy.allows(EnterpriseFeature.LOYALTY, NOW)).isTrue();
    }

    private Map<String, Object> claims() {
        var claims = new LinkedHashMap<String, Object>();
        claims.put("version", 1);
        claims.put("licenseId", "6ce81dab-9e43-42e3-8a6b-04276f4e2048");
        claims.put("installationId", INSTALLATION.toString());
        claims.put("issuedAt", NOW.getEpochSecond());
        claims.put("notBefore", NOW.getEpochSecond());
        claims.put("expiresAt", NOW.plusSeconds(3600).getEpochSecond());
        claims.put("graceUntil", NOW.plusSeconds(7200).getEpochSecond());
        claims.put("features", List.of("LOYALTY"));
        return claims;
    }

    private byte[] sign(Map<String, Object> claims) throws Exception { return sign(claims, issuer); }
    private byte[] sign(Map<String, Object> claims, KeyPair key) throws Exception {
        return signPayload(mapper.writeValueAsBytes(claims), key);
    }
    private byte[] signPayload(byte[] payload, KeyPair key) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(key.getPrivate());
        signature.update(payload);
        return mapper.writeValueAsBytes(Map.of("keyId", "issuer-v1", "payload", encode(payload), "signature", encode(signature.sign())));
    }
    private void invalid(byte[] bytes) {
        assertThatThrownBy(() -> verifier.verify(bytes, NOW)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid signed entitlement").hasNoCause();
    }
    private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static KeyPair keyPair() {
        try { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
}
