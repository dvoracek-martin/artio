package io.artio.pricing;
import io.artio.kernel.ValueObject; import java.util.Objects; import java.util.UUID;
public record TaxRuleId(UUID value) implements ValueObject { public TaxRuleId { Objects.requireNonNull(value, "Tax rule ID is required"); } }
