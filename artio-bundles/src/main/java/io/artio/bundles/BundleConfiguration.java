package io.artio.bundles;
import java.math.BigDecimal; import java.util.*;
/** Validated choices for one configured bundle instance. */
public record BundleConfiguration(BundleId bundleId,List<BundleComponent> components) {
    public BundleConfiguration { Objects.requireNonNull(bundleId);components=List.copyOf(components); }
    public Map<UUID,BigDecimal> inventoryRequirements(BigDecimal bundleQuantity) {
        Objects.requireNonNull(bundleQuantity);if(bundleQuantity.signum()<=0||bundleQuantity.precision()>15||bundleQuantity.stripTrailingZeros().scale()>4)throw new IllegalArgumentException("Bundle quantity must be positive, with at most 15 digits and 4 decimal places");
        var merged=new TreeMap<UUID,BigDecimal>();components.forEach(c->merged.merge(c.sellableId(),c.quantity().multiply(bundleQuantity),BigDecimal::add));return Collections.unmodifiableMap(merged);
    }
    public BundleQuote quote(BundlePricingRule rule,Map<UUID,io.artio.kernel.Money> unitPrices,BigDecimal bundleQuantity){
        Objects.requireNonNull(rule);Objects.requireNonNull(unitPrices);Objects.requireNonNull(bundleQuantity);if(bundleQuantity.signum()<=0||bundleQuantity.precision()>15||bundleQuantity.stripTrailingZeros().scale()>4)throw new IllegalArgumentException("Bundle quantity must be positive, with at most 15 digits and 4 decimal places");
        io.artio.kernel.Money componentTotal=null;
        if(rule.mode()!=BundlePricingRule.Mode.FIXED_PRICE){
            for(var component:components){var unit=unitPrices.get(component.sellableId());if(unit==null)throw new IllegalArgumentException("Missing component price for sellable: "+component.sellableId());var line=new io.artio.kernel.Money(unit.amount().multiply(component.quantity()),unit.currency());if(componentTotal==null)componentTotal=line;else{if(!componentTotal.currency().equals(line.currency()))throw new IllegalArgumentException("Bundle component prices must use one currency");componentTotal=new io.artio.kernel.Money(componentTotal.amount().add(line.amount()),componentTotal.currency());}}
            if(componentTotal==null)throw new IllegalStateException("Bundle configuration has no components");
        }
        var perBundle=switch(rule.mode()){case COMPONENT_SUM->componentTotal;case FIXED_PRICE->rule.fixedPrice();case PERCENTAGE_OFF_COMPONENTS->new io.artio.kernel.Money(componentTotal.amount().multiply(java.math.BigDecimal.ONE.subtract(rule.discount().value().movePointLeft(2))),componentTotal.currency());};
        if(componentTotal!=null&&!componentTotal.currency().equals(perBundle.currency()))throw new IllegalArgumentException("Fixed bundle price currency must match component prices");
        int scale=perBundle.currency().getDefaultFractionDigits();if(scale<0)scale=2;perBundle=new io.artio.kernel.Money(perBundle.amount().setScale(scale,java.math.RoundingMode.HALF_EVEN),perBundle.currency());
        var total=new io.artio.kernel.Money(perBundle.amount().multiply(bundleQuantity).setScale(scale,java.math.RoundingMode.HALF_EVEN),perBundle.currency());
        if(componentTotal!=null)componentTotal=new io.artio.kernel.Money(componentTotal.amount().setScale(scale,java.math.RoundingMode.HALF_EVEN),componentTotal.currency());
        return new BundleQuote(componentTotal,perBundle,total);
    }
}
