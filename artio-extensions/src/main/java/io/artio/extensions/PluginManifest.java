package io.artio.extensions;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Declarative, versioned metadata supplied by a Docker extension image. */
public record PluginManifest(
        String manifestVersion,
        String id,
        String name,
        String vendor,
        String version,
        String description,
        String minimumCoreVersion,
        String maximumCoreVersion,
        String image,
        List<Dependency> dependencies,
        List<String> permissions,
        Map<String, ConfigField> configuration,
        List<String> extensionPoints,
        String healthPath,
        String diagnosticsPath) {

    public PluginManifest {
        Objects.requireNonNull(manifestVersion, "Manifest version is required");
        Objects.requireNonNull(id, "Plugin id is required");
        Objects.requireNonNull(name, "Plugin name is required");
        Objects.requireNonNull(vendor, "Plugin vendor is required");
        Objects.requireNonNull(version, "Plugin version is required");
        Objects.requireNonNull(description, "Plugin description is required");
        Objects.requireNonNull(minimumCoreVersion, "Minimum Core version is required");
        Objects.requireNonNull(maximumCoreVersion, "Maximum Core version is required");
        Objects.requireNonNull(image, "Image identity is required");
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
        extensionPoints = extensionPoints == null ? List.of() : List.copyOf(extensionPoints);
    }

    public record Dependency(String id, String minimumVersion, String maximumVersion) { }

    public record ConfigField(String type, boolean required, boolean secret, String label,
                              String description, String format, List<String> options,
                              Integer minimumLength, Integer maximumLength,
                              Double minimum, Double maximum) {
        public ConfigField {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }
}
