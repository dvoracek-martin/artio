package io.artio.bundles;
import io.artio.kernel.Money; import io.artio.kernel.Percentage; import java.util.*;
/** Pricing strategy only; actual component prices are supplied by the pricing bounded context. */
public record BundlePricingRule(Mode mode,Money fixedPrice,Percentage discount,Map<String,Money> marketFixedPrices) {
    public enum Mode { COMPONENT_SUM, FIXED_PRICE, PERCENTAGE_OFF_COMPONENTS }
    public BundlePricingRule {
        Objects.requireNonNull(mode); marketFixedPrices=marketFixedPrices==null?Map.of():normalize(marketFixedPrices);
        if(mode==Mode.FIXED_PRICE){Objects.requireNonNull(fixedPrice,"Default fixed price is required");if(discount!=null)throw new IllegalArgumentException("Fixed pricing cannot include a discount");}
        else{if(fixedPrice!=null||!marketFixedPrices.isEmpty())throw new IllegalArgumentException("Component pricing cannot include a fixed price");if(mode==Mode.PERCENTAGE_OFF_COMPONENTS)Objects.requireNonNull(discount,"Discount percentage is required");else if(discount!=null)throw new IllegalArgumentException("Component sum does not accept a discount");}
    }
    public BundlePricingRule(Mode mode,Money fixedPrice,Percentage discount){this(mode,fixedPrice,discount,Map.of());}
    public static BundlePricingRule componentSum(){return new BundlePricingRule(Mode.COMPONENT_SUM,null,null,Map.of());}
    public static BundlePricingRule fixed(Money price){return fixed(price,Map.of());}
    public static BundlePricingRule fixed(Money price,Map<String,Money> marketPrices){return new BundlePricingRule(Mode.FIXED_PRICE,price,null,marketPrices);}
    public static BundlePricingRule percentageOff(Percentage discount){return new BundlePricingRule(Mode.PERCENTAGE_OFF_COMPONENTS,null,discount,Map.of());}
    public Money fixedPriceForMarket(String market){return market==null?fixedPrice:marketFixedPrices.getOrDefault(market.strip().toUpperCase(Locale.ROOT),fixedPrice);}
    private static Map<String,Money> normalize(Map<String,Money> prices){var normalized=new TreeMap<String,Money>();prices.forEach((market,price)->{if(market==null||!market.strip().matches("(?i)[a-z]{2}")||price==null)throw new IllegalArgumentException("Market prices require an ISO country code and amount");String code=market.strip().toUpperCase(Locale.ROOT);if(normalized.putIfAbsent(code,price)!=null)throw new IllegalArgumentException("Duplicate market price: "+code);});return Collections.unmodifiableMap(normalized);}
}
