package io.artio.api.pricing;

import io.artio.api.catalog.CatalogNotFoundException;
import io.artio.kernel.Money; import io.artio.kernel.Percentage; import io.artio.kernel.Quantity;
import io.artio.pricing.*; import io.artio.pricing.ports.*;
import java.math.BigDecimal; import java.time.Instant; import java.util.Currency; import java.util.List; import java.util.UUID;
import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.Cacheable; import org.springframework.cache.annotation.CacheEvict;

@Service @Transactional
public class PricingApplicationService {
    private final PriceRepository prices; private final DiscountRuleRepository discounts; private final TaxRuleRepository taxes; private final PricingEngine engine = new PricingEngine();
    public PricingApplicationService(PriceRepository prices, DiscountRuleRepository discounts, TaxRuleRepository taxes) { this.prices=prices; this.discounts=discounts; this.taxes=taxes; }
    @Transactional(readOnly=true) public List<PriceView> prices(){return prices.findAll().stream().map(PriceView::from).toList();}
    @CacheEvict(cacheNames="price-lookups",allEntries=true) public PriceView createPrice(PriceRequest r){var p=Price.create(new PriceId(UUID.randomUUID()),r.sellableId(),r.market(),r.channel(),new Money(r.amount(),Currency.getInstance(r.currency())),effective(r.validFrom()),r.validUntil());prices.save(p);return PriceView.from(p);}
    @CacheEvict(cacheNames="price-lookups",allEntries=true) public PriceView updatePrice(UUID id, PriceUpdateRequest r){var p=prices.findById(new PriceId(id)).orElseThrow(()->new CatalogNotFoundException("Price not found: "+id));p.changeAmount(new Money(r.amount(),Currency.getInstance(r.currency())));prices.save(p);return PriceView.from(p);}
    @CacheEvict(cacheNames="price-lookups",allEntries=true) public PriceView setPriceActive(UUID id, boolean active){var p=prices.findById(new PriceId(id)).orElseThrow(()->new CatalogNotFoundException("Price not found: "+id));p.setActive(active);prices.save(p);return PriceView.from(p);}
    @Transactional(readOnly=true) public List<DiscountView> discounts(){return discounts.findAll().stream().map(DiscountView::from).toList();}
    @CacheEvict(cacheNames="price-lookups",allEntries=true) public DiscountView createDiscount(DiscountRequest r){var d=DiscountRule.create(new DiscountRuleId(UUID.randomUUID()),r.code(),r.sellableId(),r.market(),new Percentage(r.percentage()),r.priority(),effective(r.validFrom()),r.validUntil());discounts.save(d);return DiscountView.from(d);}
    @CacheEvict(cacheNames="price-lookups",allEntries=true) public DiscountView setDiscountActive(UUID id,boolean active){var d=discounts.findById(new DiscountRuleId(id)).orElseThrow(()->new CatalogNotFoundException("Discount rule not found: "+id));d.setActive(active);discounts.save(d);return DiscountView.from(d);}
    @Transactional(readOnly=true) public List<TaxView> taxRules(){return taxes.findAll().stream().map(TaxView::from).toList();}
    @CacheEvict(cacheNames="price-lookups",allEntries=true) public TaxView createTaxRule(TaxRequest r){var t=TaxRule.create(new TaxRuleId(UUID.randomUUID()),r.jurisdiction(),new Percentage(r.percentage()),effective(r.validFrom()),r.validUntil());taxes.save(t);return TaxView.from(t);}
    @CacheEvict(cacheNames="price-lookups",allEntries=true) public TaxView setTaxActive(UUID id,boolean active){var t=taxes.findById(new TaxRuleId(id)).orElseThrow(()->new CatalogNotFoundException("Tax rule not found: "+id));t.setActive(active);taxes.save(t);return TaxView.from(t);}
    @Transactional(readOnly=true) @Cacheable(cacheNames="price-lookups",key="#r.sellableId()+':'+#r.market()+':'+#r.channel()+':'+#r.jurisdiction()+':'+#r.quantity()",unless="#r.at()!=null") public PricingQuote quote(QuoteRequest r){var at=r.at()==null?Instant.now():r.at();var market=Price.marketCode(r.market());var channel=Price.channelCode(r.channel());var jurisdiction=Price.jurisdictionCode(r.jurisdiction());var applicable=prices.findApplicable(r.sellableId(),market,channel,at);if(applicable.isEmpty())throw new CatalogNotFoundException("No active price found for sellable "+r.sellableId());var price=applicable.getFirst();var applicableTax=taxes.findAll().stream().filter(t->t.applies(jurisdiction,at)).max(java.util.Comparator.comparing(TaxRule::getValidFrom)).orElse(null);return engine.calculate(r.sellableId(),price.getAmount(),new Quantity(r.quantity()),market,jurisdiction,at,discounts.findAll(),applicableTax);}
    private static Instant effective(Instant value){return value==null?Instant.now():value;}
    public record PriceRequest(UUID sellableId,String market,String channel,BigDecimal amount,String currency,Instant validFrom,Instant validUntil){}
    public record PriceUpdateRequest(BigDecimal amount,String currency){}
    public record DiscountRequest(String code,UUID sellableId,String market,BigDecimal percentage,int priority,Instant validFrom,Instant validUntil){}
    public record TaxRequest(String jurisdiction,BigDecimal percentage,Instant validFrom,Instant validUntil){}
    public record QuoteRequest(UUID sellableId,String market,String channel,String jurisdiction,BigDecimal quantity,Instant at){}
    public record PriceView(UUID id,UUID sellableId,String market,String channel,BigDecimal amount,String currency,Instant validFrom,Instant validUntil,boolean active){static PriceView from(Price p){return new PriceView(p.getId().value(),p.getSellableId(),p.getMarket(),p.getChannel(),p.getAmount().amount(),p.getAmount().currency().getCurrencyCode(),p.getValidFrom(),p.getValidUntil(),p.isActive());}}
    public record DiscountView(UUID id,String code,UUID sellableId,String market,BigDecimal percentage,int priority,Instant validFrom,Instant validUntil,boolean active){static DiscountView from(DiscountRule d){return new DiscountView(d.getId().value(),d.getCode(),d.getSellableId(),d.getMarket(),d.getPercentage().value(),d.getPriority(),d.getValidFrom(),d.getValidUntil(),d.isActive());}}
    public record TaxView(UUID id,String jurisdiction,BigDecimal percentage,Instant validFrom,Instant validUntil,boolean active){static TaxView from(TaxRule t){return new TaxView(t.getId().value(),t.getJurisdiction(),t.getRate().value(),t.getValidFrom(),t.getValidUntil(),t.isActive());}}
}
