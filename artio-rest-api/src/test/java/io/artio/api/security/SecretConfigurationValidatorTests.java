package io.artio.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SecretConfigurationValidatorTests {
    @Test
    void acceptsConfiguredRandomMaterial() {
        String secret = "8e9a6bfb0ba34b17af34d78c810d037ffc641ef0cc8280bb";

        assertEquals(secret, SecretConfigurationValidator.requireStrongSecret(secret, "ARTIO_JWT_SECRET"));
    }

    @Test
    void rejectsSecretsShorterThanThirtyTwoBytes() {
        assertThrows(IllegalStateException.class,
                () -> SecretConfigurationValidator.requireStrongSecret("short-secret", "ARTIO_JWT_SECRET"));
    }

    @Test
    void rejectsDocumentedPlaceholderValues() {
        assertThrows(IllegalStateException.class,
                () -> SecretConfigurationValidator.requireStrongSecret(
                        "replace-this-local-secret-with-at-least-32-random-bytes", "ARTIO_JWT_SECRET"));
    }
}
