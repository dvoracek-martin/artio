package io.artio.catalog;

import java.util.Objects;

/** Search-engine presentation metadata for a product's canonical public URL. */
public record ProductSeoMetadata(String slug, String title, String description) {
    public ProductSeoMetadata {
        Objects.requireNonNull(slug, "SEO slug is required");
        slug = slug.trim();
        if (slug.length() > 160 || !slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*"))
            throw new IllegalArgumentException("SEO slug must be lowercase ASCII words separated by hyphens (1-160 characters)");
        title = bounded(title, 180, "SEO title");
        description = bounded(description, 320, "SEO description");
    }

    private static String bounded(String value, int maximum, String label) {
        if (value == null) return null;
        value = value.trim();
        if (value.isEmpty()) return null;
        if (value.length() > maximum) throw new IllegalArgumentException(label + " cannot exceed " + maximum + " characters");
        return value;
    }
}
