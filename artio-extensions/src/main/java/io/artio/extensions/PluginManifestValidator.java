package io.artio.extensions;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Applies Core-owned allowlists and structural limits to untrusted plugin metadata. */
public final class PluginManifestValidator {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*");
    private static final Pattern DIGEST_IMAGE = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._:/-]*@sha256:[a-f0-9]{64}");
    private static final Pattern SEMVER = Pattern.compile("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?");
    private static final Set<String> PERMISSIONS = Set.of("payment.process", "shipping.quote", "shipping.label",
            "catalog.read", "orders.read", "orders.write", "notifications.send");
    private static final Set<String> FIELD_TYPES = Set.of("text", "secret", "boolean", "enum", "number", "url", "json");
    private static final Set<String> EXTENSION_POINTS = Set.of("payment.provider.v1", "shipping.provider.v1",
            "catalog.enrichment.v1", "notification.channel.v1");
    private final Set<String> trustedRegistries;

    public PluginManifestValidator(Set<String> trustedRegistries) {
        this.trustedRegistries = trustedRegistries.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public List<String> validate(PluginManifest manifest, String coreVersion, Set<String> installedPluginIds) {
        List<String> errors = new ArrayList<>();
        if (!"1".equals(manifest.manifestVersion())) errors.add("manifestVersion must be 1");
        if (!ID.matcher(manifest.id()).matches()) errors.add("id must be a lowercase reverse-DNS style identifier");
        if (manifest.name().isBlank() || manifest.name().length() > 120) errors.add("name must contain 1 to 120 characters");
        if (manifest.vendor().isBlank() || manifest.vendor().length() > 120) errors.add("vendor must contain 1 to 120 characters");
        if (!isSemver(manifest.version())) errors.add("version must use semantic versioning");
        if (manifest.description().length() > 2000) errors.add("description exceeds 2000 characters");
        if (!isSemver(manifest.minimumCoreVersion()) || !isSemver(manifest.maximumCoreVersion())) errors.add("Core compatibility bounds must use semantic versions");
        else if (compare(coreVersion, manifest.minimumCoreVersion()) < 0 || compare(coreVersion, manifest.maximumCoreVersion()) > 0) errors.add("plugin is incompatible with this Core version");
        validateImage(manifest.image(), "image", errors);
        validateGeographicAvailability(manifest.geographicAvailability(), errors);
        Set<String> permissions = new HashSet<>();
        for (String permission : manifest.permissions()) {
            if (permission == null || !PERMISSIONS.contains(permission)) errors.add("unsupported permission: " + permission);
            if (!permissions.add(permission)) errors.add("duplicate permission: " + permission);
        }
        Set<String> points = new HashSet<>();
        for (String point : manifest.extensionPoints()) {
            if (point == null || !EXTENSION_POINTS.contains(point)) errors.add("unsupported extension point: " + point);
            if (!points.add(point)) errors.add("duplicate extension point: " + point);
        }
        Set<String> dependencies = new HashSet<>();
        for (var dependency : manifest.dependencies()) {
            if (dependency == null) { errors.add("dependency cannot be null"); continue; }
            String dependencyId = dependency.id();
            boolean validId = dependencyId != null && ID.matcher(dependencyId).matches();
            if (!validId) errors.add("invalid dependency id: " + dependencyId);
            if (!dependencies.add(dependencyId)) errors.add("duplicate dependency: " + dependencyId);
            if (manifest.id().equals(dependencyId)) errors.add("plugin cannot depend on itself");
            if (validId && !installedPluginIds.contains(dependencyId)) errors.add("missing dependency: " + dependencyId);
            if (!isSemver(dependency.minimumVersion()) || !isSemver(dependency.maximumVersion())) {
                errors.add("invalid dependency version range: " + dependencyId);
            } else if (compare(dependency.minimumVersion(), dependency.maximumVersion()) > 0) {
                errors.add("inverted dependency version range: " + dependencyId);
            }
        }
        if (manifest.configuration().size() > 100) errors.add("configuration schema exceeds 100 fields");
        manifest.configuration().forEach((key, field) -> validateField(key, field, errors));
        validatePath(manifest.healthPath(), "healthPath", errors);
        validatePath(manifest.diagnosticsPath(), "diagnosticsPath", errors);
        if (manifest.services().size() > 8) errors.add("plugin bundle may define at most 8 sidecar services");
        Set<String> serviceIds = new HashSet<>();
        for (var service : manifest.services()) {
            if (service == null) { errors.add("bundle service cannot be null"); continue; }
            if (service.id() == null || !service.id().matches("[a-z][a-z0-9-]{0,30}")) errors.add("bundle service id must be a lowercase service slug");
            else {
                if (service.id().equals("main")) errors.add("bundle service id 'main' is reserved for the primary plugin image");
                if (!serviceIds.add(service.id())) errors.add("duplicate bundle service id: " + service.id());
            }
            validateImage(service.image(), "bundle service image", errors);
            if (service.port() < 1 || service.port() > 65535) errors.add("bundle service port must be between 1 and 65535");
            validatePath(service.healthPath(), "bundle service healthPath", errors);
        }
        return List.copyOf(errors);
    }

    private static void validateGeographicAvailability(PluginManifest.GeographicAvailability availability,
            List<String> errors) {
        if (availability == null) {
            errors.add("geographicAvailability is required");
            return;
        }
        if (availability.scope() == null || !Set.of("GLOBAL", "COUNTRY_SPECIFIC").contains(availability.scope())) {
            errors.add("geographicAvailability.scope must be GLOBAL or COUNTRY_SPECIFIC");
            return;
        }
        Set<String> countries = new HashSet<>();
        Set<String> isoCountries = Set.of(Locale.getISOCountries());
        for (String country : availability.countries()) {
            if (country == null || !country.matches("[A-Z]{2}") || !isoCountries.contains(country)) {
                errors.add("geographicAvailability countries must use ISO 3166-1 alpha-2 codes");
            }
            if (country != null && !countries.add(country)) errors.add("geographicAvailability contains duplicate country: " + country);
        }
        if ("GLOBAL".equals(availability.scope()) && !availability.countries().isEmpty()) {
            errors.add("GLOBAL geographicAvailability must not list countries");
        }
        if ("COUNTRY_SPECIFIC".equals(availability.scope()) && availability.countries().isEmpty()) {
            errors.add("COUNTRY_SPECIFIC geographicAvailability must list at least one country");
        }
    }

    private boolean isTrusted(String image) {
        String registry = image.substring(0, image.indexOf('/'));
        return trustedRegistries.contains(registry.toLowerCase(Locale.ROOT));
    }

    private void validateImage(String image, String field, List<String> errors) {
        if (image == null || !DIGEST_IMAGE.matcher(image).matches()) errors.add(field + " must be pinned by sha256 digest");
        else if (!isTrusted(image)) errors.add(field + " registry is not trusted");
    }

    private static void validateField(String key, PluginManifest.ConfigField field, List<String> errors) {
        if (!key.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,63}")) errors.add("invalid configuration field key: " + key);
        if (field.type() == null || !FIELD_TYPES.contains(field.type())) errors.add("unsupported type for configuration field: " + key);
        if (field.label() == null || field.label().isBlank() || field.label().length() > 120) errors.add("invalid label for configuration field: " + key);
        if (field.description() != null && field.description().length() > 500) errors.add("description too long for configuration field: " + key);
        if (field.secret() != "secret".equals(field.type())) errors.add("secret flag/type mismatch for configuration field: " + key);
        if (field.minimumLength() != null && field.minimumLength() < 0 || field.maximumLength() != null && field.maximumLength() > 10000) errors.add("invalid string length bound for configuration field: " + key);
        if (field.minimumLength() != null && field.maximumLength() != null && field.minimumLength() > field.maximumLength()) errors.add("inverted string length range for configuration field: " + key);
        if (field.minimum() != null && field.maximum() != null && field.minimum() > field.maximum()) errors.add("inverted numeric range for configuration field: " + key);
        if ("enum".equals(field.type()) && (field.options().isEmpty() || field.options().size() > 100 || field.options().stream().anyMatch(String::isBlank))) errors.add("enum requires 1 to 100 non-empty options: " + key);
        if (field.format() != null && !Set.of("email", "uri", "hostname").contains(field.format())) errors.add("unsupported format for configuration field: " + key);
    }

    private static boolean isSemver(String version) {
        return version != null && SEMVER.matcher(version).matches();
    }

    private static void validatePath(String path, String field, List<String> errors) {
        try {
            URI uri = URI.create(path);
            if (!path.startsWith("/") || path.startsWith("//") || uri.isAbsolute() || path.contains("..") || path.length() > 200) errors.add(field + " must be a bounded absolute path within the plugin container");
        } catch (RuntimeException ex) { errors.add(field + " is invalid"); }
    }

    private static int compare(String left, String right) {
        String[] l = left.split("[-+]")[0].split("\\."), r = right.split("[-+]")[0].split("\\.");
        for (int i = 0; i < 3; i++) { int c = Integer.compare(Integer.parseInt(l[i]), Integer.parseInt(r[i])); if (c != 0) return c; }
        return 0;
    }
}
