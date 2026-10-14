package io.artio.api.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LicenseVerificationKeysTests {
    @TempDir Path temporary;

    @Test
    void acceptsOnlyExplicitPublicEd25519TrustAnchorsAndSupportsIssuerKeyRotation() throws Exception {
        var first = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var second = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path file = temporary.resolve("keys.json");
        Files.writeString(file, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                "issuer-v1", Base64.getEncoder().encodeToString(first.getPublic().getEncoded()),
                "issuer-v2", Base64.getEncoder().encodeToString(second.getPublic().getEncoded()))));
        assertThat(LicenseVerificationKeys.load(file.toString())).containsEntry("issuer-v1", first.getPublic())
                .containsEntry("issuer-v2", second.getPublic());
        assertThat(LicenseVerificationKeys.load("")).isEmpty();
    }

    @Test
    void rejectsPrivateKeysWrongAlgorithmsDuplicateIdsAndOversizedConfigurationWithoutLeakingContents() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        Path file = temporary.resolve("keys.json");
        for (String invalid : java.util.List.of(
                "{\"issuer\":\"" + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\"}",
                "{\"issuer\":\"" + Base64.getEncoder().encodeToString(rsa.getPublic().getEncoded()) + "\"}",
                "{\"issuer\":\"" + publicKey + "\",\"issuer\":\"" + publicKey + "\"}",
                "{\"../issuer\":\"" + publicKey + "\"}",
                "{} {}", "x".repeat(16_385))) {
            Files.writeString(file, invalid);
            assertThatThrownBy(() -> LicenseVerificationKeys.load(file.toString())).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid license verification key configuration").hasNoCause();
        }
    }
}
