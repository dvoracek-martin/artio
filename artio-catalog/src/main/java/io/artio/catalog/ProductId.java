package io.artio.catalog;

import io.artio.kernel.ValueObject;
import java.util.Objects;
import java.util.UUID;

public record ProductId(UUID value) implements ValueObject {
    public ProductId {
        Objects.requireNonNull(value, "Product ID is required");
    }
}
