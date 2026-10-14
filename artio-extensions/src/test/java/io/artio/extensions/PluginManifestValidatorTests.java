package io.artio.extensions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PluginManifestValidatorTests {
    private final PluginManifestValidator validator = new PluginManifestValidator(Set.of("ghcr.io"));

    @Test
    void acceptsDigestPinnedTrustedManifestWithinCoreCompatibility() {
        var manifest = validManifest();
        assertThat(validator.validate(manifest, "0.1.0", Set.of())).isEmpty();
    }

    @Test
    void acceptsCountrySpecificAvailabilityAndRejectsInvalidOrContradictoryMetadata() {
        var ch = withAvailability(new PluginManifest.GeographicAvailability("COUNTRY_SPECIFIC", List.of("CH")));
        assertThat(validator.validate(ch, "0.1.0", Set.of())).isEmpty();
        assertThat(ch.geographicAvailability().appliesTo("CH")).isTrue();
        assertThat(ch.geographicAvailability().appliesTo("DE")).isFalse();

        var invalidCountry = withAvailability(new PluginManifest.GeographicAvailability("COUNTRY_SPECIFIC", List.of("ch", "XX")));
        assertThat(validator.validate(invalidCountry, "0.1.0", Set.of()))
                .contains("geographicAvailability countries must use ISO 3166-1 alpha-2 codes");

        var contradictoryGlobal = withAvailability(new PluginManifest.GeographicAvailability("GLOBAL", List.of("CH")));
        assertThat(validator.validate(contradictoryGlobal, "0.1.0", Set.of()))
                .contains("GLOBAL geographicAvailability must not list countries");
    }

    @Test
    void rejectsMutableImagesUntrustedRegistriesAndUnsupportedPrivileges() {
        var manifest = new PluginManifest("1", "payments.stripe", "Stripe", "Artio", "1.0.0", "Payment adapter",
                "0.1.0", "1.9.0", "docker.io/acme/stripe:latest", List.of(), List.of("host.docker.internal", "orders.write"),
                Map.of(), List.of("payment.provider.v1"), "/health", "/diagnostics");

        assertThat(validator.validate(manifest, "0.1.0", Set.of()))
                .contains("image must be pinned by sha256 digest")
                .contains("unsupported permission: host.docker.internal");
    }

    @Test
    void rejectsIncompatibleCoreMissingDependenciesAndUnsafeSchema() {
        var invalidConfig = new PluginManifest.ConfigField("secret", true, false, "API key", "Provider credential", null, List.of(), null, null, null, null);
        var manifest = new PluginManifest("2", "payments.stripe", "Stripe", "Artio", "1.0.0", "Payment adapter",
                "1.0.0", "2.0.0", "ghcr.io/artio/stripe@sha256:" + "a".repeat(64),
                List.of(new PluginManifest.Dependency("common.logger", "1.0.0", "2.0.0")), List.of("payment.process"),
                Map.of("secret", invalidConfig), List.of("payment.provider.v1"), "https://example.test/health", "/diagnostics");

        assertThat(validator.validate(manifest, "0.1.0", Set.of()))
                .contains("manifestVersion must be 1")
                .contains("plugin is incompatible with this Core version")
                .contains("missing dependency: common.logger")
                .contains("secret flag/type mismatch for configuration field: secret")
                .contains("healthPath must be a bounded absolute path within the plugin container");
    }

    @Test
    void rejectsSelfAndDuplicateDependenciesAndExtensionPoints() {
        var dependency = new PluginManifest.Dependency("payments.stripe", "1.0.0", "2.0.0");
        var manifest = new PluginManifest("1", "payments.stripe", "Stripe", "Artio", "1.0.0", "Payment adapter",
                "0.1.0", "1.0.0", validManifest().image(), List.of(dependency, dependency), List.of("payment.process"),
                Map.of(), List.of("payment.provider.v1", "payment.provider.v1"), "/health", "/diagnostics");

        assertThat(validator.validate(manifest, "0.1.0", Set.of("payments.stripe")))
                .contains("plugin cannot depend on itself", "duplicate dependency: payments.stripe", "duplicate extension point: payment.provider.v1");
    }

    private static PluginManifest validManifest() {
        return new PluginManifest("1", "payments.stripe", "Stripe", "Artio", "1.0.0", "Payment adapter",
                "0.1.0", "1.0.0", "ghcr.io/artio/stripe@sha256:" + "a".repeat(64), List.of(), List.of("payment.process"),
                Map.of("apiKey", new PluginManifest.ConfigField("secret", true, true, "Secret API key", "Stripe key", null, List.of(), null, null, null, null)),
                List.of("payment.provider.v1"), "/health", "/diagnostics");
    }

    private static PluginManifest withAvailability(PluginManifest.GeographicAvailability availability) {
        var manifest = validManifest();
        return new PluginManifest(manifest.manifestVersion(), manifest.id(), manifest.name(), manifest.vendor(),
                manifest.version(), manifest.description(), manifest.minimumCoreVersion(), manifest.maximumCoreVersion(),
                manifest.image(), manifest.dependencies(), manifest.permissions(), manifest.configuration(),
                manifest.extensionPoints(), manifest.healthPath(), manifest.diagnosticsPath(), availability);
    }
}
