package io.artio.catalog;

import io.artio.kernel.ValueObject;
import java.util.Locale;
import java.util.Set;

/** Supported catalog content locales. Region formatting remains a separate concern. */
public record LocaleCode(String value) implements ValueObject {
    public static final Set<String> SUPPORTED = Set.of("de", "it", "fr", "en");

    public LocaleCode {
        if (value == null || !SUPPORTED.contains(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Locale must be one of: de, it, fr, en");
        }
        value = value.toLowerCase(Locale.ROOT);
    }
}
