package io.artio.catalog.postgres;
import io.artio.catalog.*; import io.artio.catalog.ports.AttributeDefinitionRepository; import java.util.*; import org.springframework.jdbc.core.JdbcTemplate; import org.springframework.jdbc.core.RowMapper; import org.springframework.stereotype.Repository;
@Repository public class AttributeDefinitionPostgresRepository implements AttributeDefinitionRepository {
    private static final RowMapper<AttributeDefinition> ROW = (r, n) -> AttributeDefinition.reconstitute(new AttributeDefinitionId(r.getObject("id", UUID.class)), new AttributeCode(r.getString("code")), r.getString("label"), AttributeDefinition.Type.valueOf(r.getString("type")), r.getBoolean("required"), r.getBoolean("active"));
    private final JdbcTemplate jdbc; public AttributeDefinitionPostgresRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<AttributeDefinition> findById(AttributeDefinitionId id) { return jdbc.query("SELECT * FROM catalog_attribute_definition WHERE id = ?", ROW, id.value()).stream().findFirst(); }
    public List<AttributeDefinition> findAll() { return jdbc.query("SELECT * FROM catalog_attribute_definition ORDER BY code", ROW); }
    public void save(AttributeDefinition a) { jdbc.update("INSERT INTO catalog_attribute_definition(id,code,label,type,required,active) VALUES (?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET label=EXCLUDED.label,active=EXCLUDED.active", a.getId().value(), a.getCode().value(), a.getLabel(), a.getType().name(), a.isRequired(), a.isActive()); }
}
