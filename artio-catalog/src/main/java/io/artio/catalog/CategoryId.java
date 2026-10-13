package io.artio.catalog;

import io.artio.kernel.ValueObject;
import java.util.Objects;
import java.util.UUID;

public record CategoryId(UUID value) implements ValueObject {
    public CategoryId { Objects.requireNonNull(value, "Category ID is required"); }
}
