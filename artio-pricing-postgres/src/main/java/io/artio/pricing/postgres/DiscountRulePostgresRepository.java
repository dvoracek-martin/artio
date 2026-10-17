package io.artio.pricing.postgres;
import io.artio.kernel.Percentage; import io.artio.pricing.*; import io.artio.pricing.ports.DiscountRuleRepository; import java.sql.Timestamp; import java.time.Instant; import java.util.*; import org.springframework.jdbc.core.JdbcTemplate; import org.springframework.jdbc.core.RowMapper; import org.springframework.stereotype.Repository;
@Repository public class DiscountRulePostgresRepository implements DiscountRuleRepository {
    private static final RowMapper<DiscountRule> ROW = (r,n) -> DiscountRule.reconstitute(new DiscountRuleId(r.getObject("id",UUID.class)),r.getString("code"),r.getObject("sellable_id",UUID.class),r.getString("market"),new Percentage(r.getBigDecimal("percentage")),r.getInt("priority"),r.getTimestamp("valid_from").toInstant(),r.getTimestamp("valid_until")==null?null:r.getTimestamp("valid_until").toInstant(),(Integer)r.getObject("usage_limit"),r.getBoolean("active"));
    private final JdbcTemplate jdbc; public DiscountRulePostgresRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<DiscountRule> findById(DiscountRuleId id){return jdbc.query("SELECT * FROM pricing_discount_rule WHERE id=?",ROW,id.value()).stream().findFirst();}
    public List<DiscountRule> findAll(){return jdbc.query("SELECT * FROM pricing_discount_rule ORDER BY priority,code",ROW);}
    public List<DiscountRule> findAvailableForUse(){return jdbc.query("SELECT d.* FROM pricing_discount_rule d WHERE d.usage_limit IS NULL OR d.usage_limit > "
            + "(SELECT count(*) FROM pricing_discount_redemptions r JOIN orders o ON o.id=r.order_id WHERE r.discount_rule_id=d.id AND o.status<>'CANCELLED') "
            + "ORDER BY d.priority,d.code",ROW);}
    public void lockLimitedRules(){jdbc.query("SELECT id FROM pricing_discount_rule WHERE active=true AND usage_limit IS NOT NULL ORDER BY id FOR UPDATE",
            (rs,n)->rs.getObject(1,UUID.class));}
    public void redeemAppliedRules(UUID orderId,java.util.Collection<UUID> ruleIds){
        if(orderId==null||ruleIds==null||ruleIds.isEmpty())return;
        ruleIds.stream().filter(java.util.Objects::nonNull).distinct().sorted().forEach(id->jdbc.update(
                "INSERT INTO pricing_discount_redemptions(discount_rule_id,order_id) SELECT id,? FROM pricing_discount_rule WHERE id=? AND usage_limit IS NOT NULL ON CONFLICT DO NOTHING",
                orderId,id));
    }
    public void save(DiscountRule d){jdbc.update("INSERT INTO pricing_discount_rule(id,code,sellable_id,market,percentage,priority,valid_from,valid_until,usage_limit,active) VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET active=EXCLUDED.active",d.getId().value(),d.getCode(),d.getSellableId(),d.getMarket(),d.getPercentage().value(),d.getPriority(),Timestamp.from(d.getValidFrom()),d.getValidUntil()==null?null:Timestamp.from(d.getValidUntil()),d.getUsageLimit(),d.isActive());}
}
