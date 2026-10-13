package io.artio.pricing;
import io.artio.kernel.ValueObject; import java.util.Objects; import java.util.UUID;
public record DiscountRuleId(UUID value) implements ValueObject { public DiscountRuleId { Objects.requireNonNull(value, "Discount rule ID is required"); } }
