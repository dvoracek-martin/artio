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
    void validatesOptionalBundleServicesWithoutChangingSingleImagePlugins() {
        var manifest = validManifest();
        var bundle = new PluginManifest(manifest.manifestVersion(), manifest.id(), manifest.name(), manifest.vendor(),
                manifest.version(), manifest.description(), manifest.minimumCoreVersion(), manifest.maximumCoreVersion(),
                manifest.image(), manifest.dependencies(), manifest.permissions(), manifest.configuration(),
                manifest.extensionPoints(), manifest.healthPath(), manifest.diagnosticsPath(), manifest.geographicAvailability(),
                List.of(new PluginManifest.BundleService("metrics", "ghcr.io/artio/metrics@sha256:" + "b".repeat(64), 9090, "/-/healthy")));
        assertThat(validator.validate(bundle, "0.1.0", Set.of())).isEmpty();

        var invalid = new PluginManifest(bundle.manifestVersion(), bundle.id(), bundle.name(), bundle.vendor(), bundle.version(),
                bundle.description(), bundle.minimumCoreVersion(), bundle.maximumCoreVersion(), bundle.image(),
                bundle.dependencies(), bundle.permissions(), bundle.configuration(), bundle.extensionPoints(),
                bundle.healthPath(), bundle.diagnosticsPath(), bundle.geographicAvailability(), List.of(
                new PluginManifest.BundleService("metrics", "docker.io/metrics:latest", 70000, "//host/admin"),
                new PluginManifest.BundleService("metrics", "docker.io/metrics:latest", 0, "/health")));
        assertThat(validator.validate(invalid, "0.1.0", Set.of()))
                .contains("bundle service port must be between 1 and 65535",
                        "bundle service healthPath must be a bounded absolute path within the plugin container",
                        "bundle service image must be pinned by sha256 digest", "duplicate bundle service id: metrics");
        var invalidSlug = new PluginManifest.BundleService("Metrics", "ghcr.io/artio/metrics@sha256:" + "b".repeat(64), 9090, "/health");
        var invalidSlugManifest = new PluginManifest(bundle.manifestVersion(), bundle.id(), bundle.name(), bundle.vendor(),
                bundle.version(), bundle.description(), bundle.minimumCoreVersion(), bundle.maximumCoreVersion(),
                bundle.image(), bundle.dependencies(), bundle.permissions(), bundle.configuration(), bundle.extensionPoints(),
                bundle.healthPath(), bundle.diagnosticsPath(), bundle.geographicAvailability(), List.of(invalidSlug));
        assertThat(validator.validate(invalidSlugManifest, "0.1.0", Set.of()))
                .contains("bundle service id must be a lowercase service slug");
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

    @Test
    void reportsMalformedNullableFieldsInsteadOfThrowing() {
        var manifest = validManifest();
        var malformed = new PluginManifest(manifest.manifestVersion(), manifest.id(), manifest.name(), manifest.vendor(),
                manifest.version(), manifest.description(), manifest.minimumCoreVersion(), manifest.maximumCoreVersion(),
                manifest.image(), List.of(new PluginManifest.Dependency(null, null, "1.0.0")), manifest.permissions(),
                Map.of("apiKey", new PluginManifest.ConfigField(null, true, true, "API key", null, null,
                        List.of(), null, null, null, null)), manifest.extensionPoints(), manifest.healthPath(),
                manifest.diagnosticsPath());

        assertThat(validator.validate(malformed, "0.1.0", Set.of()))
                .contains("invalid dependency id: null", "invalid dependency version range: null",
                        "unsupported type for configuration field: apiKey");
    }

    @Test
    void rejectsInvertedDependencyVersionRange() {
        var manifest = validManifest();
        var malformed = new PluginManifest(manifest.manifestVersion(), manifest.id(), manifest.name(), manifest.vendor(),
                manifest.version(), manifest.description(), manifest.minimumCoreVersion(), manifest.maximumCoreVersion(),
                manifest.image(), List.of(new PluginManifest.Dependency("common.logger", "2.0.0", "1.0.0")),
                manifest.permissions(), manifest.configuration(), manifest.extensionPoints(), manifest.healthPath(),
                manifest.diagnosticsPath());
        assertThat(validator.validate(malformed, "0.1.0", Set.of("common.logger")))
                .contains("inverted dependency version range: common.logger");
    }

    @Test
    void validatesPrimaryAndSidecarEnvironmentBindingsAgainstScalarSettings() {
        var base = validManifest();
        var service = new PluginManifest.BundleService("dashboard",
                "ghcr.io/artio/dashboard@sha256:" + "b".repeat(64), 3000, "/api/health",
                Map.of("GF_SECURITY_ADMIN_PASSWORD", "apiKey"));
        var valid = new PluginManifest(base.manifestVersion(), base.id(), base.name(), base.vendor(), base.version(),
                base.description(), base.minimumCoreVersion(), base.maximumCoreVersion(), base.image(),
                base.dependencies(), base.permissions(), base.configuration(), base.extensionPoints(), base.healthPath(),
                base.diagnosticsPath(), base.geographicAvailability(), List.of(service), Map.of());
        assertThat(validator.validate(valid, "0.1.0", Set.of())).isEmpty();

        var invalid = new PluginManifest(base.manifestVersion(), base.id(), base.name(), base.vendor(), base.version(),
                base.description(), base.minimumCoreVersion(), base.maximumCoreVersion(), base.image(),
                base.dependencies(), base.permissions(), base.configuration(), base.extensionPoints(), base.healthPath(),
                base.diagnosticsPath(), base.geographicAvailability(), List.of(new PluginManifest.BundleService("dashboard",
                service.image(), 3000, "/api/health", Map.of("admin.password", "missingField"))),
                Map.of("unsafe-name", "missingField"));
        assertThat(validator.validate(invalid, "0.1.0", Set.of()))
                .contains("primary service has an invalid environment variable name",
                        "primary service environment binding references an unknown configuration field",
                        "bundle service dashboard has an invalid environment variable name",
                        "bundle service dashboard environment binding references an unknown configuration field");
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
