package io.artio.bundles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.artio.kernel.Money;import io.artio.kernel.Percentage;import java.math.BigDecimal;import java.util.Currency;import java.util.List;import java.util.Map;import java.util.Set;import java.util.UUID;
import org.junit.jupiter.api.Test;

class BundleTests {
    @Test void configurableBundleValidatesChoicesAndPoolsRepeatedInventoryRequirements(){
        UUID laptop=UUID.randomUUID(),memory8=UUID.randomUUID(),memory16=UUID.randomUUID();
        var groups=List.of(new BundleOptionGroup("computer","Computer",1,1,List.of(new BundleChoice(laptop,BigDecimal.ONE))),new BundleOptionGroup("memory","Memory",1,1,List.of(new BundleChoice(memory8,BigDecimal.ONE),new BundleChoice(memory16,BigDecimal.ONE))),new BundleOptionGroup("accessory","Accessory",0,1,List.of(new BundleChoice(laptop,new BigDecimal("0.5")))));
        var bundle=Bundle.configurable(new BundleId(UUID.randomUUID()),new BundleName("Creator kit"),new BundleSku("BND-001"),groups,BundlePricingRule.percentageOff(new Percentage(new BigDecimal("10"))));
        var configuration=bundle.configure(Map.of("computer",Set.of(laptop),"memory",Set.of(memory16),"accessory",Set.of(laptop)));
        assertThat(configuration.inventoryRequirements(new BigDecimal("2"))).containsEntry(laptop,new BigDecimal("3.0")).containsEntry(memory16,new BigDecimal("2"));
        assertThatThrownBy(()->bundle.configure(Map.of("computer",Set.of(laptop),"memory",Set.of()))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void bundleDiscountAppliesToComponentSumAndBundleQuantity(){
        UUID one=UUID.randomUUID(),two=UUID.randomUUID();
        var fixed=Bundle.fixed(new BundleId(UUID.randomUUID()),new BundleName("Pair"),new BundleSku("PAIR-1"),List.of(new BundleComponent(one,BigDecimal.ONE),new BundleComponent(two,new BigDecimal("2"))),BundlePricingRule.percentageOff(new Percentage(new BigDecimal("15"))));
        var config=fixed.configure(Map.of());
        var quote=config.quote(fixed.getPricingRule(),Map.of(one,money("10"),two,money("5")),new BigDecimal("3"));
        assertThat(quote.componentTotal().amount()).isEqualByComparingTo("20.00");
        assertThat(quote.perBundlePrice().amount()).isEqualByComparingTo("17.00");
        assertThat(quote.totalPrice().amount()).isEqualByComparingTo("51.00");
    }
    @Test void fixedPriceRuleDoesNotRequireComponentPrices(){
        var bundle=Bundle.fixed(new BundleId(UUID.randomUUID()),new BundleName("Starter"),new BundleSku("START-1"),List.of(new BundleComponent(UUID.randomUUID(),BigDecimal.ONE)),BundlePricingRule.fixed(money("25")));
        var quote=bundle.configure(Map.of()).quote(bundle.getPricingRule(),Map.of(),BigDecimal.ONE);
        assertThat(quote.componentTotal()).isNull();assertThat(quote.totalPrice().amount()).isEqualByComparingTo("25.00");
    }
    @Test void bundleCannotModelAnEmptySelectionOrProductSkuFormat(){
        var emptyOptional=List.of(new BundleOptionGroup("extra","Extra",0,1,List.of(new BundleChoice(UUID.randomUUID(),BigDecimal.ONE))));
        assertThatThrownBy(()->Bundle.configurable(new BundleId(UUID.randomUUID()),new BundleName("Empty"),new BundleSku("BND-002"),emptyOptional,BundlePricingRule.componentSum())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new BundleSku("lowercase")).isInstanceOf(IllegalArgumentException.class);
    }
    private Money money(String amount){return new Money(new BigDecimal(amount),Currency.getInstance("USD"));}
}
