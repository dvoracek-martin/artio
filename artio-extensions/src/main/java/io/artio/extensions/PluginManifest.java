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
        String diagnosticsPath,
        GeographicAvailability geographicAvailability,
        List<BundleService> services) {

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
        services = services == null ? List.of() : List.copyOf(services);
    }

    /** Source-compatible constructor for manifests created before geographic metadata was added. */
    public PluginManifest(String manifestVersion, String id, String name, String vendor, String version,
            String description, String minimumCoreVersion, String maximumCoreVersion, String image,
            List<Dependency> dependencies, List<String> permissions, Map<String, ConfigField> configuration,
            List<String> extensionPoints, String healthPath, String diagnosticsPath) {
        this(manifestVersion, id, name, vendor, version, description, minimumCoreVersion, maximumCoreVersion,
                image, dependencies, permissions, configuration, extensionPoints, healthPath, diagnosticsPath,
                GeographicAvailability.global(), List.of());
    }

    /** Source-compatible constructor for v1 manifests with geographic metadata. */
    public PluginManifest(String manifestVersion, String id, String name, String vendor, String version,
            String description, String minimumCoreVersion, String maximumCoreVersion, String image,
            List<Dependency> dependencies, List<String> permissions, Map<String, ConfigField> configuration,
            List<String> extensionPoints, String healthPath, String diagnosticsPath,
            GeographicAvailability geographicAvailability) {
        this(manifestVersion, id, name, vendor, version, description, minimumCoreVersion, maximumCoreVersion,
                image, dependencies, permissions, configuration, extensionPoints, healthPath, diagnosticsPath,
                geographicAvailability, List.of());
    }

    public record Dependency(String id, String minimumVersion, String maximumVersion) { }

    /** Optional private, non-invokable sidecar belonging to this plugin installation. */
    public record BundleService(String id, String image, int port, String healthPath) { }

    /** Discovery hint only. It never restricts a merchant from enabling another supported market. */
    public record GeographicAvailability(String scope, List<String> countries) {
        public GeographicAvailability {
            countries = countries == null ? List.of() : List.copyOf(countries);
        }

        public static GeographicAvailability global() {
            return new GeographicAvailability("GLOBAL", List.of());
        }

        public boolean appliesTo(String country) {
            return "GLOBAL".equals(scope) || country != null && countries.contains(country);
        }
    }

    public record ConfigField(String type, boolean required, boolean secret, String label,
                              String description, String format, List<String> options,
                              Integer minimumLength, Integer maximumLength,
                              Double minimum, Double maximum) {
        public ConfigField {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }
}
