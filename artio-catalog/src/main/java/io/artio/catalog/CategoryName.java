package io.artio.catalog;

import io.artio.kernel.ValueObject;

public record CategoryName(String value) implements ValueObject {
    public CategoryName {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Category name is required");
        value = value.trim();
        if (value.length() > 200) throw new IllegalArgumentException("Category name cannot exceed 200 characters");
    }
}
