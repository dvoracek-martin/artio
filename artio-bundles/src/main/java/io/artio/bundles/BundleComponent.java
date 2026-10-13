package io.artio.bundles;
import java.math.BigDecimal; import java.util.Objects; import java.util.UUID;
/** A sellable reference and the number required in the bundle. */
public record BundleComponent(UUID sellableId,BigDecimal quantity) { public BundleComponent { Objects.requireNonNull(sellableId,"Sellable reference is required");Objects.requireNonNull(quantity,"Component quantity is required");if(quantity.signum()<=0||quantity.precision()>15||quantity.stripTrailingZeros().scale()>4)throw new IllegalArgumentException("Component quantity must be positive, with at most 15 digits and 4 decimal places"); } }
