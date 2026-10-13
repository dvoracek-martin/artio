package io.artio.bundles;
import io.artio.kernel.Money; import io.artio.kernel.Percentage; import java.util.Objects;
/** Pricing strategy only; actual component prices are supplied by the pricing bounded context. */
public record BundlePricingRule(Mode mode,Money fixedPrice,Percentage discount) {
    public enum Mode { COMPONENT_SUM, FIXED_PRICE, PERCENTAGE_OFF_COMPONENTS }
    public BundlePricingRule { Objects.requireNonNull(mode);if(mode==Mode.FIXED_PRICE){Objects.requireNonNull(fixedPrice,"Fixed price is required");if(discount!=null)throw new IllegalArgumentException("Fixed pricing cannot include a discount");}else{if(fixedPrice!=null)throw new IllegalArgumentException("Component pricing cannot include a fixed price");if(mode==Mode.PERCENTAGE_OFF_COMPONENTS)Objects.requireNonNull(discount,"Discount percentage is required");else if(discount!=null)throw new IllegalArgumentException("Component sum does not accept a discount");} }
    public static BundlePricingRule componentSum(){return new BundlePricingRule(Mode.COMPONENT_SUM,null,null);}
    public static BundlePricingRule fixed(Money price){return new BundlePricingRule(Mode.FIXED_PRICE,price,null);}
    public static BundlePricingRule percentageOff(Percentage discount){return new BundlePricingRule(Mode.PERCENTAGE_OFF_COMPONENTS,null,discount);}
}
