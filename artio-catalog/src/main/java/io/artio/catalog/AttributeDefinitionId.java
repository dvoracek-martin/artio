package io.artio.catalog;
import io.artio.kernel.ValueObject;
import java.util.Objects;
import java.util.UUID;
public record AttributeDefinitionId(UUID value) implements ValueObject { public AttributeDefinitionId { Objects.requireNonNull(value, "Attribute definition ID is required"); } }
