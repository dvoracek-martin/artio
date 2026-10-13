package io.artio.catalog;

import io.artio.kernel.ValueObject;
import java.util.Objects;
import java.util.UUID;

public record VariantId(UUID value) implements ValueObject {
    public VariantId {
        Objects.requireNonNull(value, "Variant ID is required");
    }
}