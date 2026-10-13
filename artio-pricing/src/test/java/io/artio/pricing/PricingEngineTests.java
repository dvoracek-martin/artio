package io.artio.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.artio.kernel.Money; import io.artio.kernel.Percentage; import io.artio.kernel.Quantity;
import java.math.BigDecimal; import java.time.Instant; import java.util.Currency; import java.util.List; import java.util.UUID;
import org.junit.jupiter.api.Test;

class PricingEngineTests {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    @Test void calculatesStackedDiscountsThenTaxOnDiscountedSubtotal() {
        UUID item = UUID.randomUUID();
        var first = DiscountRule.create(new DiscountRuleId(UUID.randomUUID()), "TEN", item, "US", new Percentage(new BigDecimal("10")), 1, NOW, null);
        var second = DiscountRule.create(new DiscountRuleId(UUID.randomUUID()), "FIVE", null, null, new Percentage(new BigDecimal("5")), 2, NOW, null);
        var tax = TaxRule.create(new TaxRuleId(UUID.randomUUID()), "US-CA", new Percentage(new BigDecimal("8.25")), NOW, null);
        var quote = new PricingEngine().calculate(item, dollars("100.00"), new Quantity(new BigDecimal("2")), "US", "US-CA", NOW, List.of(second, first), tax);
        assertThat(quote.lineListPrice().amount()).isEqualByComparingTo("200.00");
        assertThat(quote.discounts().amount()).isEqualByComparingTo("29.00");
        assertThat(quote.taxableAmount().amount()).isEqualByComparingTo("171.00");
        assertThat(quote.tax().amount()).isEqualByComparingTo("14.11");
        assertThat(quote.total().amount()).isEqualByComparingTo("185.11");
    }
    @Test void expiredRulesDoNotApply() {
        UUID item = UUID.randomUUID();
        var discount = DiscountRule.create(new DiscountRuleId(UUID.randomUUID()), "OLD", item, "US", new Percentage(new BigDecimal("50")), 0, NOW.minusSeconds(60), NOW);
        var quote = new PricingEngine().calculate(item, dollars("10.00"), new Quantity(BigDecimal.ONE), "US", "US-CA", NOW, List.of(discount), null);
        assertThat(quote.total().amount()).isEqualByComparingTo("10.00");
    }
    @Test void appliesDiscountToRoundedLineAmountForFractionalQuantities() {
        UUID item = UUID.randomUUID();
        var discount = DiscountRule.create(new DiscountRuleId(UUID.randomUUID()), "TEN", item, "US", new Percentage(new BigDecimal("10")), 0, NOW, null);
        var quote = new PricingEngine().calculate(item, dollars("0.05"), new Quantity(new BigDecimal("3")), "US", "US-CA", NOW, List.of(discount), null);
        assertThat(quote.lineListPrice().amount()).isEqualByComparingTo("0.15");
        assertThat(quote.discounts().amount()).isEqualByComparingTo("0.02");
        assertThat(quote.total().amount()).isEqualByComparingTo("0.13");
    }
    @Test void quoteCarriesTheEffectiveTaxPolicyProvenance() {
        UUID item=UUID.randomUUID();Instant starts=NOW.minusSeconds(3600);Instant ends=NOW.plusSeconds(3600);
        var tax=TaxRule.create(new TaxRuleId(UUID.randomUUID()),"CH-ZH",new Percentage(new BigDecimal("8.1")),starts,ends);
        var quote=new PricingEngine().calculate(item,dollars("100.00"),new Quantity(BigDecimal.ONE),"CH","CH-ZH",NOW,List.of(),tax);
        assertThat(quote.jurisdiction()).isEqualTo("CH-ZH");
        assertThat(quote.taxRatePercent()).isEqualByComparingTo("8.1");
        assertThat(quote.taxRuleValidFrom()).isEqualTo(starts);
        assertThat(quote.taxRuleValidUntil()).isEqualTo(ends);
        assertThat(quote.pricedAt()).isEqualTo(NOW);
    }
    @Test void rejectsInvalidPricePeriodAndMarketCodes() {
        assertThatThrownBy(() -> Price.create(new PriceId(UUID.randomUUID()), UUID.randomUUID(), "us", "WEB", dollars("1"), NOW, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Price.create(new PriceId(UUID.randomUUID()), UUID.randomUUID(), "US", "WEB", dollars("1"), NOW, NOW)).isInstanceOf(IllegalArgumentException.class);
    }
    private Money dollars(String value) { return new Money(new BigDecimal(value), Currency.getInstance("USD")); }
}
