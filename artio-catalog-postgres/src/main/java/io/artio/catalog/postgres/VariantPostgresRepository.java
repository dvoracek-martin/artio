package io.artio.catalog.postgres;

import io.artio.catalog.ProductId;
import io.artio.catalog.Sku;
import io.artio.catalog.Variant;
import io.artio.catalog.VariantId;
import io.artio.catalog.VariantName;
import io.artio.catalog.ports.VariantRepository;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class VariantPostgresRepository implements VariantRepository {
    private static final RowMapper<Variant> VARIANT_ROW = (result, row) -> Variant.reconstitute(
            new VariantId(result.getObject("id", UUID.class)),
            new ProductId(result.getObject("product_id", UUID.class)),
            new VariantName(result.getString("name")),
            new Sku(result.getString("sku")),
            result.getBoolean("active"),
            result.getTimestamp("created_at").toInstant(),
            result.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public VariantPostgresRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Variant> findById(VariantId id) {
        return jdbc.query("SELECT * FROM catalog_variant WHERE id = ?", VARIANT_ROW, id.value())
                .stream().findFirst();
    }

    @Override
    public List<Variant> findByProductId(ProductId productId) {
        return jdbc.query("SELECT * FROM catalog_variant WHERE product_id = ? ORDER BY created_at, id",
                VARIANT_ROW, productId.value());
    }

    @Override
    public void save(Variant variant) {
        jdbc.update("""
                INSERT INTO catalog_variant (id, product_id, name, sku, active, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    product_id = EXCLUDED.product_id,
                    name = EXCLUDED.name,
                    sku = EXCLUDED.sku,
                    active = EXCLUDED.active,
                    updated_at = EXCLUDED.updated_at
                """,
                variant.getId().value(), variant.getProductId().value(), variant.getName().value(),
                variant.getSku().value(), variant.isActive(), Timestamp.from(variant.getCreatedAt()),
                Timestamp.from(variant.getUpdatedAt()));
    }
}