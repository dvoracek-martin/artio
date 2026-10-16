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

    private static PluginManifest packageManifest(String version) {
        return new PluginManifest(version, "payments.example", "Example payments", "Example Ltd", "1.0.0",
                "Provider", "0.1.0", "9.0.0",
                "ghcr.io/example/payments@sha256:" + "a".repeat(64), List.of(), List.of(), Map.of(), List.of(), "/health", "/diagnostics");
    }
}
