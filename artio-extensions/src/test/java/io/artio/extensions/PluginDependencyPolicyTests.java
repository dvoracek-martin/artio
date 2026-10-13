package io.artio.extensions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PluginDependencyPolicyTests {
    private final PluginManifest plugin = manifest("shipping.example", List.of(
            new PluginManifest.Dependency("common.transport", "1.0.0", "2.0.0")));

    @Test
    void requiresInstalledCompatibleDependencyForInstallAndEnabledForActivation() {
        var configured = Map.of("common.transport", new PluginDependencyPolicy.InstalledPlugin("1.5.0", "CONFIGURED"));
        assertThat(PluginDependencyPolicy.validate(plugin, configured, false)).containsExactly("dependency is not installed: common.transport");

        var installed = Map.of("common.transport", new PluginDependencyPolicy.InstalledPlugin("1.5.0", "INSTALLED"));
        assertThat(PluginDependencyPolicy.validate(plugin, installed, false)).isEmpty();
        assertThat(PluginDependencyPolicy.validate(plugin, installed, true)).containsExactly("dependency is not enabled: common.transport");

        var enabled = Map.of("common.transport", new PluginDependencyPolicy.InstalledPlugin("1.5.0", "ENABLED"));
        assertThat(PluginDependencyPolicy.validate(plugin, enabled, true)).isEmpty();
    }

    @Test
    void rejectsDependencyOutsideItsVersionRange() {
        var installed = Map.of("common.transport", new PluginDependencyPolicy.InstalledPlugin("2.1.0", "ENABLED"));
        assertThat(PluginDependencyPolicy.validate(plugin, installed, true))
                .containsExactly("dependency version is outside the declared range: common.transport");

        PluginManifest prereleaseRange = manifest("shipping.prerelease", List.of(
                new PluginManifest.Dependency("common.transport", "1.0.0-rc.2", "1.0.0-rc.9")));
        var prerelease = Map.of("common.transport", new PluginDependencyPolicy.InstalledPlugin("1.0.0-rc.10", "ENABLED"));
        assertThat(PluginDependencyPolicy.validate(prereleaseRange, prerelease, true))
                .containsExactly("dependency version is outside the declared range: common.transport");
    }

    @Test
    void findsEnabledDependentsBeforeDependencyShutdown() {
        var dependent = manifest("shipping.dependent", List.of(
                new PluginManifest.Dependency("common.transport", "1.0.0", "2.0.0")));
        assertThat(PluginDependencyPolicy.enabledDependents("common.transport", List.of(dependent, plugin)))
                .containsExactly("shipping.dependent", "shipping.example");
    }

    private static PluginManifest manifest(String id, List<PluginManifest.Dependency> dependencies) {
        return new PluginManifest("1", id, id, "Artio", "1.0.0", "Test extension", "0.1.0", "1.0.0",
                "ghcr.io/artio/test@sha256:" + "a".repeat(64), dependencies, List.of(), Map.of(), List.of(), "/health", "/diagnostics");
    }
}
