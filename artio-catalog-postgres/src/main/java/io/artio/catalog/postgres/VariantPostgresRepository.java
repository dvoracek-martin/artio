package io.artio.catalog.postgres;

import io.artio.catalog.ProductId;
import io.artio.catalog.Sku;
import io.artio.catalog.Variant;
import io.artio.catalog.VariantId;
import io.artio.catalog.VariantName;
import io.artio.catalog.ports.VariantRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class VariantPostgresRepository implements VariantRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public VariantPostgresRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Optional<Variant> findById(VariantId id) {
        return jdbc.query("SELECT * FROM catalog_variant WHERE id = ?", this::mapVariant, id.value())
                .stream().findFirst();
    }

    @Override
    public List<Variant> findByProductId(ProductId productId) {
        return jdbc.query("SELECT * FROM catalog_variant WHERE product_id = ? ORDER BY created_at, id",
                this::mapVariant, productId.value());
    }

    @Override
    public void save(Variant variant) {
        jdbc.update("""
                INSERT INTO catalog_variant (id, product_id, name, sku, active, created_at, updated_at, attributes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (id) DO UPDATE SET
                    product_id = EXCLUDED.product_id,
                    name = EXCLUDED.name,
                    sku = EXCLUDED.sku,
                    active = EXCLUDED.active,
                    updated_at = EXCLUDED.updated_at,
                    attributes = EXCLUDED.attributes
                """,
                variant.getId().value(), variant.getProductId().value(), variant.getName().value(),
                variant.getSku().value(), variant.isActive(), Timestamp.from(variant.getCreatedAt()),
                Timestamp.from(variant.getUpdatedAt()), writeAttributes(variant.getAttributes()));
    }

    private Variant mapVariant(java.sql.ResultSet result, int row) throws java.sql.SQLException {
        try {
            Map<String, String> attributes = mapper.readValue(result.getString("attributes"), new TypeReference<>() { });
            return Variant.reconstitute(new VariantId(result.getObject("id", UUID.class)),
                    new ProductId(result.getObject("product_id", UUID.class)), new VariantName(result.getString("name")),
                    new Sku(result.getString("sku")), attributes, result.getBoolean("active"),
                    result.getTimestamp("created_at").toInstant(), result.getTimestamp("updated_at").toInstant());
        } catch (JsonProcessingException invalid) {
            throw new java.sql.SQLException("Stored variant attributes are invalid", invalid);
        }
    }

    private String writeAttributes(Map<String, String> attributes) {
        try { return mapper.writeValueAsString(attributes); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Variant attributes could not be serialized", invalid); }
    }
}
