package io.artio.catalog;

import io.artio.kernel.ValueObject;

public record Sku(String value) implements ValueObject {
    public Sku {
        if (value == null || !value.matches("[A-Z0-9-]{3,50}")) {
            throw new IllegalArgumentException("Invalid SKU format");
        }
    }
}
