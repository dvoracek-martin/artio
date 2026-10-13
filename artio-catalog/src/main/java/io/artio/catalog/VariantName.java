package io.artio.catalog;

import io.artio.kernel.ValueObject;

public record VariantName(String value) implements ValueObject {
    public VariantName {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Variant name required");
        }
        if (value.length() > 500) {
            throw new IllegalArgumentException("Variant name too long");
        }
    }
}