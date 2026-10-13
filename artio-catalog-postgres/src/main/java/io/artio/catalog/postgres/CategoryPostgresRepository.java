package io.artio.catalog.postgres;
import io.artio.catalog.*; import io.artio.catalog.ports.CategoryRepository; import java.util.*; import org.springframework.jdbc.core.JdbcTemplate; import org.springframework.jdbc.core.RowMapper; import org.springframework.stereotype.Repository;
@Repository public class CategoryPostgresRepository implements CategoryRepository {
    private static final RowMapper<Category> ROW = (r, n) -> Category.reconstitute(new CategoryId(r.getObject("id", UUID.class)), new CategoryName(r.getString("name")), r.getObject("parent_id", UUID.class) == null ? null : new CategoryId(r.getObject("parent_id", UUID.class)), r.getBoolean("active"));
    private final JdbcTemplate jdbc; public CategoryPostgresRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<Category> findById(CategoryId id) { return jdbc.query("SELECT * FROM catalog_category WHERE id = ?", ROW, id.value()).stream().findFirst(); }
    public List<Category> findAll() { return jdbc.query("SELECT * FROM catalog_category ORDER BY name, id", ROW); }
    public void save(Category c) { jdbc.update("INSERT INTO catalog_category(id,name,parent_id,active) VALUES (?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=EXCLUDED.name,parent_id=EXCLUDED.parent_id,active=EXCLUDED.active", c.getId().value(), c.getName().value(), c.getParentId() == null ? null : c.getParentId().value(), c.isActive()); }
}
