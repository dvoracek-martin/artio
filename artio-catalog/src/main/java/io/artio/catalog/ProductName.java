package io.artio.catalog;

import io.artio.kernel.ValueObject;

public record ProductName(String value) implements ValueObject {
    public ProductName {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Name required");
        }
        if (value.length() > 500) {
            throw new IllegalArgumentException("Name too long");
        }
    }
}
