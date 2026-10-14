package io.artio.extensions;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates user-supplied settings against the already-approved manifest schema. */
public final class PluginConfigurationValidator {
    public ValidationResult validate(PluginManifest manifest, Map<String, Object> submitted,
                                     Set<String> alreadyConfiguredSecrets) {
        Map<String, Object> values = submitted == null ? Map.of() : submitted;
        Set<String> existing = alreadyConfiguredSecrets == null ? Set.of() : alreadyConfiguredSecrets;
        List<String> errors = new ArrayList<>();
        Map<String, Object> publicValues = new LinkedHashMap<>();
        Map<String, String> newSecrets = new LinkedHashMap<>();

        for (String key : values.keySet()) {
            if (!manifest.configuration().containsKey(key)) errors.add("unknown configuration field: " + key);
        }
        manifest.configuration().forEach((key, field) -> {
            Object value = values.get(key);
            if ("secret".equals(field.type())) {
                if (value == null || value instanceof String text && text.isEmpty()) {
                    if (field.required() && !existing.contains(key)) errors.add("required secret is not configured: " + key);
                    return;
                }
                if (!(value instanceof String text)) {
                    errors.add("secret value must be text: " + key);
                    return;
                }
                if (text.length() > 10000) errors.add("secret value is too long: " + key);
                else newSecrets.put(key, text);
                return;
            }
            if (value == null) {
                if (field.required()) errors.add("required configuration value is missing: " + key);
                return;
            }
            if (validateValue(key, field, value, errors)) publicValues.put(key, value);
        });
        return new ValidationResult(errors.isEmpty(), errors, publicValues, newSecrets);
    }

    private static boolean validateValue(String key, PluginManifest.ConfigField field, Object value, List<String> errors) {
        if (value instanceof String text && text.length() > 10_000
                || value instanceof Number number && number.toString().length() > 10_000) {
            errors.add("configuration value is too long: " + key);
            return false;
        }
        switch (field.type()) {
            case "text" -> {
                if (!(value instanceof String text)) { errors.add("configuration value must be text: " + key); return false; }
                int length = text.length();
                if (field.minimumLength() != null && length < field.minimumLength() || field.maximumLength() != null && length > field.maximumLength()) {
                    errors.add("text length is outside allowed bounds: " + key); return false;
                }
                return true;
            }
            case "boolean" -> {
                if (!(value instanceof Boolean)) { errors.add("configuration value must be boolean: " + key); return false; }
                return true;
            }
            case "enum" -> {
                if (!(value instanceof String text) || !field.options().contains(text)) { errors.add("configuration value is not an allowed option: " + key); return false; }
                return true;
            }
            case "number" -> {
                BigDecimal number;
                try { number = value instanceof Number numeric ? new BigDecimal(numeric.toString()) : null; }
                catch (NumberFormatException ex) { number = null; }
                if (number == null) { errors.add("configuration value must be numeric: " + key); return false; }
                if (field.minimum() != null && number.compareTo(BigDecimal.valueOf(field.minimum())) < 0
                        || field.maximum() != null && number.compareTo(BigDecimal.valueOf(field.maximum())) > 0) {
                    errors.add("number is outside allowed bounds: " + key); return false;
                }
                return true;
            }
            case "url" -> {
                if (!(value instanceof String text)) { errors.add("configuration value must be a URL: " + key); return false; }
                try {
                    URI uri = URI.create(text);
                    if (!Set.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
                } catch (RuntimeException ex) { errors.add("configuration value must be an absolute HTTP(S) URL without user info: " + key); return false; }
                return true;
            }
            case "json" -> {
                if (!(value instanceof Map<?, ?> || value instanceof List<?> || value instanceof String
                        || value instanceof Number || value instanceof Boolean)) {
                    errors.add("configuration value must be JSON: " + key); return false;
                }
                return true;
            }
            default -> { errors.add("unsupported configuration type: " + key); return false; }
        }
    }

    public record ValidationResult(boolean valid, List<String> errors,
                                   Map<String, Object> publicValues, Map<String, String> newSecrets) {
        public ValidationResult {
            errors = List.copyOf(errors);
            publicValues = Map.copyOf(publicValues);
            newSecrets = Map.copyOf(newSecrets);
        }
    }
}
