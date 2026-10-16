package io.artio.extensions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PluginManifestV2ValidatorTests {
    private final PluginManifestV2Validator validator = new PluginManifestV2Validator();

    @Test
    void validatesOnlyExplicitCoreRegisteredOperationsAndPermissions() {
        var valid = new PluginManifestV2(packageManifest("2"), List.of(
                new PluginManifestV2.CapabilityDeclaration("payment.provider.v1",
                        List.of("createCheckout", "refund"), List.of("payment.process"),
                        Map.of("createCheckout", "a".repeat(64))),
                new PluginManifestV2.CapabilityDeclaration("shipping.provider.v1",
                        List.of("quote"), List.of("shipping.quote"), Map.of())), List.of());
        assertThat(validator.validate(valid)).isEmpty();
    }

    @Test
    void rejectsUnknownCapabilitiesOperationsAndImplicitSubscriptionPermissions() {
        var invalid = new PluginManifestV2(packageManifest("2"), List.of(
                new PluginManifestV2.CapabilityDeclaration("payment.future.v1", List.of("refund"), List.of("payment.process"), Map.of()),
                new PluginManifestV2.CapabilityDeclaration("payment.provider.v1", List.of("refund", "refund"), List.of(), Map.of("missing", "x"))),
                List.of(new PluginManifestV2.EventSubscription("customer.exported", 1, List.of("customers.read"))));
        assertThat(validator.validate(invalid)).contains(
                "unsupported capability: payment.future.v1",
                "duplicate capability operation: payment.provider.v1/refund",
                "capability operation requires declared permission: payment.process",
                "account fingerprint references an undeclared operation: missing",
                "account fingerprint must be a lowercase sha256 value",
                "unsupported event subscription: customer.exported",
                "event subscriptions do not grant data permissions");
    }

    @Test
    void v1IsNotSilentlyAcceptedAsV2() {
        assertThat(validator.validate(new PluginManifestV2(packageManifest("1"), List.of(), List.of())))
                .contains("v2 package manifestVersion must be 2");
    }

    @Test
    void packageSafetyValidationIsReusedWithoutGrantingV1ExtensionPoints() {
        var v2 = new PluginManifestV2(packageManifest("2"), List.of(
                new PluginManifestV2.CapabilityDeclaration("payment.provider.v1", List.of("refund"),
                        List.of("payment.process"), Map.of())), List.of());
        var validator = new PluginManifestValidator(java.util.Set.of("ghcr.io"));
        assertThat(validator.validate(v2, "0.1.0", java.util.Set.of())).isEmpty();

        var legacy = new PluginManifest("1", "payments.example", "Example payments", "Example Ltd", "1.0.0",
                "Provider", "0.1.0", "9.0.0", "ghcr.io/example/payments@sha256:" + "a".repeat(64),
                List.of(), List.of("payment.process"), Map.of(), List.of("payment.provider.v1"), "/health", "/diagnostics");
        assertThat(validator.validate(new PluginManifestV2(legacy, List.of(), List.of()), "0.1.0", java.util.Set.of()))
                .contains("v2 manifests must declare capabilities instead of legacy extensionPoints",
                        "v2 manifests must declare permissions on capability operations");
    }

    @Test
    void validatesGeneratedSecretsAndDependencyOrderedOneShotServices() {
        String image = "ghcr.io/artio/minio@sha256:" + "a".repeat(64);
        var generated = new PluginManifest.ConfigField("secret", true, true, "Runtime access key", null,
                null, List.of(), null, null, null, null, true, 20, "runtime");
        var minio = new PluginManifest.BundleService("minio", image, 9000, "/minio/health/ready",
                Map.of(), Map.of(), List.of(), List.of("/var/lib/minio/data"), List.of(), 512, true, false);
        var provisioner = new PluginManifest.BundleService("provision", image, 0, null,
                Map.of(), Map.of("/run/secrets/access-key", "runtimeAccessKey"), List.of("minio"),
                List.of(), List.of(), 256, true, true);
        var packageManifest = new PluginManifest("2", "storage.example", "Storage", "Artio", "1.0.0",
                "Storage package", "0.1.0", "9.0.0", image, List.of(), List.of(),
                Map.of("runtimeAccessKey", generated), List.of(), "/health", "/diagnostics",
                PluginManifest.GeographicAvailability.global(), List.of(minio, provisioner), List.of(), Map.of(), Map.of());
        var capability = new PluginManifestV2.CapabilityDeclaration("storage.object.v1",
                List.of("put", "get", "head", "delete"),
                List.of("storage.object.write", "storage.object.read", "storage.object.delete"), Map.of());
        var release = new PluginManifestV2(packageManifest, List.of(capability), List.of());

        assertThat(new PluginManifestValidator(java.util.Set.of("ghcr.io"))
                .validate(release, "0.1.0", java.util.Set.of())).isEmpty();
    }

    @Test
    void generatedRotationGroupMustBeAValidGroupOnGeneratedSecrets() {
        String image = "ghcr.io/artio/minio@sha256:" + "a".repeat(64);
        var generated = new PluginManifest.ConfigField("secret", true, true, "Runtime key", null,
                null, List.of(), null, null, null, null, true, 20, "Runtime");
        var packageManifest = new PluginManifest("2", "storage.example", "Storage", "Artio", "1.0.0",
                "Storage package", "0.1.0", "9.0.0", image, List.of(), List.of(),
                Map.of("runtimeKey", generated), List.of(), "/health", "/diagnostics",
                PluginManifest.GeographicAvailability.global(), List.of(), List.of(), Map.of(), Map.of());
        var release = new PluginManifestV2(packageManifest, List.of(), List.of());

        assertThat(new PluginManifestValidator(java.util.Set.of("ghcr.io"))
                .validate(release, "0.1.0", java.util.Set.of()))
                .contains("generatedRotationGroup requires a generated secret and a valid group name: runtimeKey");
    }

    @Test
    void rejectsGeneratedSecretsAndOneShotServicesOutsideTheirV2Constraints() {
        String image = "ghcr.io/artio/minio@sha256:" + "a".repeat(64);
        var generated = new PluginManifest.ConfigField("secret", true, true, "Runtime key", null,
                null, List.of(), null, null, null, null, true, 8);
        var job = new PluginManifest.BundleService("provision", image, 0, null,
                Map.of(), Map.of(), List.of(), List.of(), List.of(), 128, true, true);
        var packageManifest = new PluginManifest("1", "storage.example", "Storage", "Artio", "1.0.0",
                "Storage package", "0.1.0", "9.0.0", image, List.of(), List.of(),
                Map.of("runtimeKey", generated), List.of(), "/health", "/diagnostics",
                PluginManifest.GeographicAvailability.global(), List.of(job), List.of(), Map.of(), Map.of());

        assertThat(new PluginManifestValidator(java.util.Set.of("ghcr.io"))
                .validate(packageManifest, "0.1.0", java.util.Set.of()))
                .contains("generated configuration secrets require manifest v2",
                        "bundle service lifecycle options require manifest v2");
    }

    private static PluginManifest packageManifest(String version) {
        return new PluginManifest(version, "payments.example", "Example payments", "Example Ltd", "1.0.0",
                "Provider", "0.1.0", "9.0.0",
                "ghcr.io/example/payments@sha256:" + "a".repeat(64), List.of(), List.of(), Map.of(), List.of(), "/health", "/diagnostics");
    }
}
