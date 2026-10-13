package io.artio.pricing.postgres;
import io.artio.kernel.Percentage; import io.artio.pricing.*; import io.artio.pricing.ports.TaxRuleRepository; import java.sql.Timestamp; import java.util.*; import org.springframework.jdbc.core.JdbcTemplate; import org.springframework.jdbc.core.RowMapper; import org.springframework.stereotype.Repository;
@Repository public class TaxRulePostgresRepository implements TaxRuleRepository {
    private static final RowMapper<TaxRule> ROW = (r,n) -> TaxRule.reconstitute(new TaxRuleId(r.getObject("id",UUID.class)),r.getString("jurisdiction"),new Percentage(r.getBigDecimal("percentage")),r.getTimestamp("valid_from").toInstant(),r.getTimestamp("valid_until")==null?null:r.getTimestamp("valid_until").toInstant(),r.getBoolean("active"));
    private final JdbcTemplate jdbc; public TaxRulePostgresRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<TaxRule> findById(TaxRuleId id){return jdbc.query("SELECT * FROM pricing_tax_rule WHERE id=?",ROW,id.value()).stream().findFirst();}
    public List<TaxRule> findAll(){return jdbc.query("SELECT * FROM pricing_tax_rule ORDER BY jurisdiction,valid_from DESC",ROW);}
    public void save(TaxRule t){jdbc.update("INSERT INTO pricing_tax_rule(id,jurisdiction,percentage,valid_from,valid_until,active) VALUES (?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET active=EXCLUDED.active",t.getId().value(),t.getJurisdiction(),t.getRate().value(),Timestamp.from(t.getValidFrom()),t.getValidUntil()==null?null:Timestamp.from(t.getValidUntil()),t.isActive());}
}
