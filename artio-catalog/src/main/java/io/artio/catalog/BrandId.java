package io.artio.catalog;
import io.artio.kernel.ValueObject;
import java.util.Objects;
import java.util.UUID;
public record BrandId(UUID value) implements ValueObject { public BrandId { Objects.requireNonNull(value, "Brand ID is required"); } }
