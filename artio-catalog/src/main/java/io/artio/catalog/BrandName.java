package io.artio.catalog;
import io.artio.kernel.ValueObject;
public record BrandName(String value) implements ValueObject {
    public BrandName { if (value == null || value.isBlank()) throw new IllegalArgumentException("Brand name is required"); value = value.trim(); if (value.length() > 200) throw new IllegalArgumentException("Brand name cannot exceed 200 characters"); }
}
