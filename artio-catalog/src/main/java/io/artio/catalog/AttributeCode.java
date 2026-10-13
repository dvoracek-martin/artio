package io.artio.catalog;
import io.artio.kernel.ValueObject;
public record AttributeCode(String value) implements ValueObject {
    public AttributeCode { if (value == null || !value.matches("[a-z][a-z0-9_]{1,63}")) throw new IllegalArgumentException("Attribute code must be lowercase alphanumeric with underscores"); }
}
