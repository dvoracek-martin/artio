package io.artio.pricing.postgres;
import io.artio.kernel.Percentage; import io.artio.pricing.*; import io.artio.pricing.ports.DiscountRuleRepository; import java.sql.Timestamp; import java.time.Instant; import java.util.*; import org.springframework.jdbc.core.JdbcTemplate; import org.springframework.jdbc.core.RowMapper; import org.springframework.stereotype.Repository;
@Repository public class DiscountRulePostgresRepository implements DiscountRuleRepository {
    private static final RowMapper<DiscountRule> ROW = (r,n) -> DiscountRule.reconstitute(new DiscountRuleId(r.getObject("id",UUID.class)),r.getString("code"),r.getObject("sellable_id",UUID.class),r.getString("market"),new Percentage(r.getBigDecimal("percentage")),r.getInt("priority"),r.getTimestamp("valid_from").toInstant(),r.getTimestamp("valid_until")==null?null:r.getTimestamp("valid_until").toInstant(),r.getBoolean("active"));
    private final JdbcTemplate jdbc; public DiscountRulePostgresRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<DiscountRule> findById(DiscountRuleId id){return jdbc.query("SELECT * FROM pricing_discount_rule WHERE id=?",ROW,id.value()).stream().findFirst();}
    public List<DiscountRule> findAll(){return jdbc.query("SELECT * FROM pricing_discount_rule ORDER BY priority,code",ROW);}
    public void save(DiscountRule d){jdbc.update("INSERT INTO pricing_discount_rule(id,code,sellable_id,market,percentage,priority,valid_from,valid_until,active) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET active=EXCLUDED.active",d.getId().value(),d.getCode(),d.getSellableId(),d.getMarket(),d.getPercentage().value(),d.getPriority(),Timestamp.from(d.getValidFrom()),d.getValidUntil()==null?null:Timestamp.from(d.getValidUntil()),d.isActive());}
}
