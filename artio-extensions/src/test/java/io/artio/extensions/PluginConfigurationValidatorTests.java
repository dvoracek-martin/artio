package io.artio.extensions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PluginConfigurationValidatorTests {
    private final PluginConfigurationValidator validator = new PluginConfigurationValidator();
    private final PluginManifest manifest = new PluginManifest("1", "payments.example", "Example", "Artio", "1.0.0",
            "Provider", "0.1.0", "1.0.0", "ghcr.io/artio/payments@sha256:" + "a".repeat(64), List.of(), List.of("payment.process"),
            Map.of("apiKey", field("secret", true, true), "mode", new PluginManifest.ConfigField("enum", true, false, "Mode", null, null, List.of("test", "live"), null, null, null, null),
                    "endpoint", field("url", true, false)), List.of("payment.provider.v1"), "/health", "/diagnostics");

    @Test
    void separatesSecretsFromPublicValuesAndAllowsExistingSecretToBeOmitted() {
        var first = validator.validate(manifest, Map.of("apiKey", "sk_test_hidden", "mode", "test", "endpoint", "https://pay.example.test"), Set.of());
        assertThat(first.valid()).isTrue();
        assertThat(first.publicValues()).containsEntry("mode", "test").containsEntry("endpoint", "https://pay.example.test").doesNotContainKey("apiKey");
        assertThat(first.newSecrets()).containsEntry("apiKey", "sk_test_hidden");

        var update = validator.validate(manifest, Map.of("mode", "live", "endpoint", "https://pay.example.test"), Set.of("apiKey"));
        assertThat(update.valid()).isTrue();
        assertThat(update.newSecrets()).isEmpty();
    }

    @Test
    void rejectsMissingRequiredValuesUnknownFieldsInvalidEnumsAndUnsafeUrls() {
        var result = validator.validate(manifest, Map.of("endpoint", "javascript:alert(1)", "mode", "admin", "unexpected", true), Set.of());
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("required secret is not configured: apiKey", "unknown configuration field: unexpected",
                "configuration value is not an allowed option: mode", "configuration value must be an absolute HTTP(S) URL without user info: endpoint");
    }

    private static PluginManifest.ConfigField field(String type, boolean required, boolean secret) {
        return new PluginManifest.ConfigField(type, required, secret, type, null, null, List.of(), null, null, null, null);
    }
}
