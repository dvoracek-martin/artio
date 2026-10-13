package io.artio.catalog;

import java.util.Map;
import java.util.Objects;

/** Locale-scoped product copy; a translation never replaces the product's stable identity or SKU. */
public record LocalizedProductContent(LocaleCode locale, String name, String description) {
    public LocalizedProductContent {
        Objects.requireNonNull(locale, "Locale is required");
        if (name == null || name.isBlank() || name.length() > 500) throw new IllegalArgumentException("Localized name must contain 1 to 500 characters");
        if (description != null && description.length() > 5000) throw new IllegalArgumentException("Localized description cannot exceed 5000 characters");
    }

    /** Resolves each field independently: requested locale, English, then the base catalog copy. */
    public static Resolved resolve(String requestedLocale, Map<LocaleCode, LocalizedProductContent> translations,
                                   String baseName, String baseDescription) {
        LocaleCode requested = new LocaleCode(requestedLocale);
        LocalizedProductContent selected = translations.get(requested);
        String name = selected == null ? null : selected.name();
        String description = selected == null ? null : selected.description();
        LocalizedProductContent english = translations.get(new LocaleCode("en"));
        if (name == null && english != null) name = english.name();
        if (description == null && english != null) description = english.description();
        return new Resolved(requested, name == null ? baseName : name,
                description == null ? baseDescription : description);
    }

    public record Resolved(LocaleCode requestedLocale, String name, String description) { }
}
