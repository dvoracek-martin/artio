package io.artio.api.bundles;

import io.artio.api.catalog.CatalogNotFoundException; import io.artio.bundles.*; import io.artio.bundles.ports.BundleRepository; import io.artio.kernel.Money; import io.artio.kernel.Percentage;
import io.artio.api.pricing.PricingApplicationService;
import java.math.BigDecimal; import java.util.*; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;

@Service @Transactional public class BundleApplicationService {
    private final BundleRepository bundles; private final PricingApplicationService pricing; public BundleApplicationService(BundleRepository bundles,PricingApplicationService pricing){this.bundles=bundles;this.pricing=pricing;}
    @Transactional(readOnly=true) public List<BundleView> findAll(){return bundles.findAll().stream().map(BundleView::from).toList();}
    @Transactional(readOnly=true) public BundleView findById(UUID id){return BundleView.from(find(id));}
    public BundleView create(BundleRequest r){var id=new BundleId(UUID.randomUUID());var pricing=pricing(r.pricingRule());Bundle bundle=switch(r.type()){case FIXED->Bundle.fixed(id,new BundleName(r.name()),new BundleSku(r.sku()),r.components()==null?List.of():r.components().stream().map(c->new BundleComponent(c.sellableId(),c.quantity())).toList(),pricing);case CONFIGURABLE->Bundle.configurable(id,new BundleName(r.name()),new BundleSku(r.sku()),r.optionGroups()==null?List.of():r.optionGroups().stream().map(BundleApplicationService::group).toList(),pricing);};bundles.save(bundle);return BundleView.from(bundle);}
    public BundleView setActive(UUID id,boolean active){var b=find(id);b.setActive(active);bundles.save(b);return BundleView.from(b);}
    @Transactional(readOnly=true) public ConfigurationView configure(UUID id,ConfigureRequest r){var b=find(id);var config=b.configure(safeSelections(r.selectedOptions()));return ConfigurationView.from(config,r.bundleQuantity());}
    @Transactional(readOnly=true) public BundleQuote quote(UUID id,BundleQuoteRequest r){var b=find(id);var config=b.configure(safeSelections(r.selectedOptions()));var prices=new HashMap<UUID,Money>();for(var item:config.components()){var quote=pricing.quote(new PricingApplicationService.QuoteRequest(item.sellableId(),r.market(),r.channel(),r.jurisdiction(),BigDecimal.ONE,null));prices.put(item.sellableId(),quote.taxableAmount());}var currencies=prices.values().stream().map(m->m.currency()).collect(java.util.stream.Collectors.toSet());if(currencies.size()>1)throw new IllegalArgumentException("Bundle component prices must use one currency");if(b.getPricingRule().mode()==BundlePricingRule.Mode.FIXED_PRICE&&!currencies.isEmpty()&&!currencies.contains(b.getPricingRule().fixedPrice().currency()))throw new IllegalArgumentException("Fixed bundle price currency must match its components");return config.quote(b.getPricingRule(),prices,r.bundleQuantity());}
    private Bundle find(UUID id){return bundles.findById(new BundleId(id)).orElseThrow(()->new CatalogNotFoundException("Bundle not found: "+id));}
    private static BundlePricingRule pricing(PricingRuleRequest r){if(r==null||r.mode()==null)throw new IllegalArgumentException("Bundle pricing rule is required");return switch(r.mode()){case COMPONENT_SUM->BundlePricingRule.componentSum();case FIXED_PRICE->BundlePricingRule.fixed(new Money(r.fixedAmount(),Currency.getInstance(r.currency())));case PERCENTAGE_OFF_COMPONENTS->BundlePricingRule.percentageOff(new Percentage(r.discountPercentage()));};}
    private static BundleOptionGroup group(OptionGroupRequest g){return new BundleOptionGroup(g.code(),g.name(),g.minimumSelections(),g.maximumSelections(),g.choices().stream().map(c->new BundleChoice(c.sellableId(),c.quantity())).toList());}
    private static Map<String,Set<UUID>> safeSelections(Map<String,Set<UUID>> selections){return selections==null?Map.of():selections;}
    public record ComponentRequest(UUID sellableId,BigDecimal quantity){}
    public record ChoiceRequest(UUID sellableId,BigDecimal quantity){}
    public record OptionGroupRequest(String code,String name,int minimumSelections,int maximumSelections,List<ChoiceRequest> choices){}
    public record PricingRuleRequest(BundlePricingRule.Mode mode,BigDecimal fixedAmount,String currency,BigDecimal discountPercentage){}
    public record BundleRequest(String name,String sku,BundleType type,List<ComponentRequest> components,List<OptionGroupRequest> optionGroups,PricingRuleRequest pricingRule){}
    public record ConfigureRequest(Map<String,Set<UUID>> selectedOptions,BigDecimal bundleQuantity){}
    public record MoneyRequest(BigDecimal amount,String currency){}
    public record BundleQuoteRequest(Map<String,Set<UUID>> selectedOptions,BigDecimal bundleQuantity,String market,String channel,String jurisdiction){}
    public record ComponentView(UUID sellableId,BigDecimal quantity){}
    public record OptionGroupView(String code,String name,int minimumSelections,int maximumSelections,List<ComponentView> choices){}
    public record BundleView(UUID id,String name,String sku,BundleType type,boolean active,List<ComponentView> components,List<OptionGroupView> optionGroups,BundlePricingRule pricingRule){static BundleView from(Bundle b){return new BundleView(b.getId().value(),b.getName().value(),b.getSku().value(),b.getType(),b.isActive(),b.getComponents().stream().map(c->new ComponentView(c.sellableId(),c.quantity())).toList(),b.getOptionGroups().stream().map(g->new OptionGroupView(g.code(),g.name(),g.minimumSelections(),g.maximumSelections(),g.choices().stream().map(c->new ComponentView(c.sellableId(),c.quantity())).toList())).toList(),b.getPricingRule());}}
    public record ConfigurationView(UUID bundleId,List<ComponentView> components,Map<UUID,BigDecimal> inventoryRequirements){static ConfigurationView from(BundleConfiguration c,BigDecimal quantity){return new ConfigurationView(c.bundleId().value(),c.components().stream().map(x->new ComponentView(x.sellableId(),x.quantity())).toList(),c.inventoryRequirements(quantity));}}
}
