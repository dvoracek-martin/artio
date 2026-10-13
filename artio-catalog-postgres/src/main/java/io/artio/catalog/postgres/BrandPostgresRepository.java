package io.artio.catalog.postgres;
import io.artio.catalog.*; import io.artio.catalog.ports.BrandRepository; import java.util.*; import org.springframework.jdbc.core.JdbcTemplate; import org.springframework.jdbc.core.RowMapper; import org.springframework.stereotype.Repository;
@Repository public class BrandPostgresRepository implements BrandRepository {
    private static final RowMapper<Brand> ROW = (r, n) -> Brand.reconstitute(new BrandId(r.getObject("id", UUID.class)), new BrandName(r.getString("name")), r.getString("description"), r.getBoolean("active"));
    private final JdbcTemplate jdbc; public BrandPostgresRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<Brand> findById(BrandId id) { return jdbc.query("SELECT * FROM catalog_brand WHERE id = ?", ROW, id.value()).stream().findFirst(); }
    public List<Brand> findAll() { return jdbc.query("SELECT * FROM catalog_brand ORDER BY name, id", ROW); }
    public void save(Brand b) { jdbc.update("INSERT INTO catalog_brand(id,name,description,active) VALUES (?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=EXCLUDED.name,description=EXCLUDED.description,active=EXCLUDED.active", b.getId().value(), b.getName().value(), b.getDescription(), b.isActive()); }
}
