package io.artio.pricing.postgres;
import io.artio.kernel.Percentage; import io.artio.pricing.*; import io.artio.pricing.ports.TaxRuleRepository; import java.sql.Timestamp; import java.util.*; import org.springframework.jdbc.core.JdbcTemplate; import org.springframework.jdbc.core.RowMapper; import org.springframework.stereotype.Repository;
@Repository public class TaxRulePostgresRepository implements TaxRuleRepository {
    private static final RowMapper<TaxRule> ROW = (r,n) -> TaxRule.reconstitute(new TaxRuleId(r.getObject("id",UUID.class)),r.getString("jurisdiction"),TaxCategory.valueOf(r.getString("tax_category")),new Percentage(r.getBigDecimal("percentage")),r.getTimestamp("valid_from").toInstant(),r.getTimestamp("valid_until")==null?null:r.getTimestamp("valid_until").toInstant(),r.getBoolean("active"));
    private final JdbcTemplate jdbc; public TaxRulePostgresRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<TaxRule> findById(TaxRuleId id){return jdbc.query("SELECT * FROM pricing_tax_rule WHERE id=?",ROW,id.value()).stream().findFirst();}
    public List<TaxRule> findAll(){return jdbc.query("SELECT * FROM pricing_tax_rule ORDER BY jurisdiction,valid_from DESC",ROW);}
    public void save(TaxRule t){jdbc.update("INSERT INTO pricing_tax_rule(id,jurisdiction,tax_category,percentage,valid_from,valid_until,active) VALUES (?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET active=EXCLUDED.active",t.getId().value(),t.getJurisdiction(),t.getCategory().name(),t.getRate().value(),Timestamp.from(t.getValidFrom()),t.getValidUntil()==null?null:Timestamp.from(t.getValidUntil()),t.isActive());}
    public TaxCategory categoryFor(UUID sellableId){return jdbc.query("SELECT category FROM pricing_sellable_tax_category WHERE sellable_id=?",rs->rs.next()?TaxCategory.valueOf(rs.getString(1)):TaxCategory.STANDARD,sellableId);}
    public void assignCategory(UUID sellableId,TaxCategory category){jdbc.update("INSERT INTO pricing_sellable_tax_category(sellable_id,category,updated_at) VALUES (?,?,now()) ON CONFLICT(sellable_id) DO UPDATE SET category=EXCLUDED.category,updated_at=now()",sellableId,category.name());}
}
