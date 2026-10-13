package io.artio.catalog;

import io.artio.kernel.ValueObject;

public record ProductDescription(String value) implements ValueObject {
    public ProductDescription {
        if (value != null && value.length() > 5_000) {
            throw new IllegalArgumentException("Description too long");
        }
    }
}
