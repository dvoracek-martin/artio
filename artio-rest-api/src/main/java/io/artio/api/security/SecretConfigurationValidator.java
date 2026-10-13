package io.artio.api.security;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Rejects weak or sample values before they are used for cryptographic keys. */
final class SecretConfigurationValidator {
    private SecretConfigurationValidator() {}

    static String requireStrongSecret(String value, String environmentVariable) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(environmentVariable + " must contain at least 32 bytes");
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("replace-")
                || normalized.startsWith("change-this-")
                || normalized.startsWith("local-development-")
                || normalized.startsWith("your-")) {
            throw new IllegalStateException(environmentVariable + " must not use a sample or placeholder value");
        }
        return value;
    }
}
