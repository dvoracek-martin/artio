package io.artio.pricing;
import io.artio.kernel.ValueObject; import java.util.Objects; import java.util.UUID;
public record PriceId(UUID value) implements ValueObject { public PriceId { Objects.requireNonNull(value, "Price ID is required"); } }
