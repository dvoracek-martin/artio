package io.artio.bundles;
import java.math.BigDecimal; import java.util.Objects; import java.util.UUID;
public record BundleChoice(UUID sellableId,BigDecimal quantity) { public BundleChoice { Objects.requireNonNull(sellableId,"Choice sellable is required");Objects.requireNonNull(quantity,"Choice quantity is required");if(quantity.signum()<=0||quantity.precision()>15||quantity.stripTrailingZeros().scale()>4)throw new IllegalArgumentException("Choice quantity must be positive, with at most 15 digits and 4 decimal places"); } }
