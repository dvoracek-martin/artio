package io.artio.bundles.postgres;

import io.artio.bundles.*; import io.artio.bundles.ports.BundleRepository; import io.artio.kernel.Money; import io.artio.kernel.Percentage;
import java.util.*; import org.springframework.jdbc.core.JdbcTemplate; import org.springframework.jdbc.core.RowMapper; import org.springframework.stereotype.Repository; import org.springframework.transaction.annotation.Transactional;

@Repository public class BundlePostgresRepository implements BundleRepository {
    private record Stored(BundleId id,BundleName name,BundleSku sku,BundleType type,boolean active,BundlePricingRule pricing){}
    private static final RowMapper<Stored> BUNDLE_ROW=(r,n)->{
        BundlePricingRule.Mode mode=BundlePricingRule.Mode.valueOf(r.getString("pricing_mode"));
        BundlePricingRule rule=switch(mode){case COMPONENT_SUM->BundlePricingRule.componentSum();case FIXED_PRICE->BundlePricingRule.fixed(new Money(r.getBigDecimal("fixed_amount"),Currency.getInstance(r.getString("currency").trim())));case PERCENTAGE_OFF_COMPONENTS->BundlePricingRule.percentageOff(new Percentage(r.getBigDecimal("discount_percentage")));};
        return new Stored(new BundleId(r.getObject("id",UUID.class)),new BundleName(r.getString("name")),new BundleSku(r.getString("sku")),BundleType.valueOf(r.getString("type")),r.getBoolean("active"),rule);
    };
    private final JdbcTemplate jdbc; public BundlePostgresRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<Bundle> findById(BundleId id){return jdbc.query("SELECT * FROM commerce_bundle WHERE id=?",BUNDLE_ROW,id.value()).stream().findFirst().map(this::hydrate);}
    public List<Bundle> findAll(){return jdbc.query("SELECT * FROM commerce_bundle ORDER BY name,id",BUNDLE_ROW).stream().map(this::hydrate).toList();}
    @Transactional public void save(Bundle bundle){var rule=bundle.getPricingRule();jdbc.update("INSERT INTO commerce_bundle(id,name,sku,type,active,pricing_mode,fixed_amount,currency,discount_percentage) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=EXCLUDED.name,active=EXCLUDED.active,pricing_mode=EXCLUDED.pricing_mode,fixed_amount=EXCLUDED.fixed_amount,currency=EXCLUDED.currency,discount_percentage=EXCLUDED.discount_percentage",bundle.getId().value(),bundle.getName().value(),bundle.getSku().value(),bundle.getType().name(),bundle.isActive(),rule.mode().name(),rule.fixedPrice()==null?null:rule.fixedPrice().amount(),rule.fixedPrice()==null?null:rule.fixedPrice().currency().getCurrencyCode(),rule.discount()==null?null:rule.discount().value());
        UUID id=bundle.getId().value();jdbc.update("DELETE FROM commerce_bundle_component WHERE bundle_id=?",id);jdbc.update("DELETE FROM commerce_bundle_option_group WHERE bundle_id=?",id);
        for(var group:bundle.getOptionGroups()){jdbc.update("INSERT INTO commerce_bundle_option_group(bundle_id,code,name,minimum_selections,maximum_selections) VALUES (?,?,?,?,?)",id,group.code(),group.name(),group.minimumSelections(),group.maximumSelections());for(var choice:group.choices())insertComponent(id,group.code(),choice.sellableId(),choice.quantity());}
        for(var component:bundle.getComponents())insertComponent(id,null,component.sellableId(),component.quantity());
    }
    private void insertComponent(UUID id,String group,UUID sellable,java.math.BigDecimal quantity){jdbc.update("INSERT INTO commerce_bundle_component(bundle_id,group_code,sellable_id,quantity) VALUES (?,?,?,?)",id,group,sellable,quantity);}
    private Bundle hydrate(Stored b){
        if(b.type()==BundleType.FIXED){var components=jdbc.query("SELECT sellable_id,quantity FROM commerce_bundle_component WHERE bundle_id=? AND group_code IS NULL ORDER BY sellable_id",(r,n)->new BundleComponent(r.getObject(1,UUID.class),r.getBigDecimal(2)),b.id().value());return Bundle.reconstitute(b.id(),b.name(),b.sku(),b.type(),components,List.of(),b.pricing(),b.active());}
        var groups=jdbc.query("SELECT code,name,minimum_selections,maximum_selections FROM commerce_bundle_option_group WHERE bundle_id=? ORDER BY code",(r,n)->{
            String code=r.getString("code");var choices=jdbc.query("SELECT sellable_id,quantity FROM commerce_bundle_component WHERE bundle_id=? AND group_code=? ORDER BY sellable_id",(cr,cn)->new BundleChoice(cr.getObject(1,UUID.class),cr.getBigDecimal(2)),b.id().value(),code);
            return new BundleOptionGroup(code,r.getString("name"),r.getInt("minimum_selections"),r.getInt("maximum_selections"),choices);
        },b.id().value());
        return Bundle.reconstitute(b.id(),b.name(),b.sku(),b.type(),List.of(),groups,b.pricing(),b.active());
    }
}
