package io.artio.catalog.postgres;

import io.artio.catalog.Product;
import io.artio.catalog.ProductDescription;
import io.artio.catalog.ProductId;
import io.artio.catalog.ProductName;
import io.artio.catalog.Sku;
import io.artio.catalog.ports.ProductRepository;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ProductPostgresRepository implements ProductRepository {
    private static final RowMapper<Product> PRODUCT_ROW = (result, row) -> Product.reconstitute(
            new ProductId(result.getObject("id", UUID.class)),
            new ProductName(result.getString("name")),
            new ProductDescription(result.getString("description")),
            new Sku(result.getString("sku")),
            result.getBoolean("active"),
            result.getTimestamp("created_at").toInstant(),
            result.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public ProductPostgresRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Product> findById(ProductId id) {
        return jdbc.query("SELECT * FROM catalog_product WHERE id = ?", PRODUCT_ROW, id.value())
                .stream().findFirst().map(this::hydrate);
    }

    @Override
    public List<Product> findAll() {
        return jdbc.query("SELECT * FROM catalog_product ORDER BY created_at DESC, id", PRODUCT_ROW)
                .stream().map(this::hydrate).toList();
    }

    @Override
    @Transactional
    public void save(Product product) {
        jdbc.update("""
                INSERT INTO catalog_product (id, name, description, sku, active, created_at, updated_at, brand_id, seo_slug, seo_title, seo_description)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    name = EXCLUDED.name,
                    description = EXCLUDED.description,
                    sku = EXCLUDED.sku,
                    active = EXCLUDED.active,
                    updated_at = EXCLUDED.updated_at,
                    brand_id = EXCLUDED.brand_id,
                    seo_slug = EXCLUDED.seo_slug,
                    seo_title = EXCLUDED.seo_title,
                    seo_description = EXCLUDED.seo_description
                """,
                product.getId().value(), product.getName().value(), product.getDescription().value(),
                product.getSku().value(), product.isActive(), Timestamp.from(product.getCreatedAt()),
                Timestamp.from(product.getUpdatedAt()), product.getBrandId() == null ? null : product.getBrandId().value(),
                product.getSeoMetadata() == null ? null : product.getSeoMetadata().slug(),
                product.getSeoMetadata() == null ? null : product.getSeoMetadata().title(),
                product.getSeoMetadata() == null ? null : product.getSeoMetadata().description());
        UUID productId = product.getId().value();
        jdbc.update("DELETE FROM catalog_product_category WHERE product_id = ?", productId);
        product.getCategoryIds().forEach(categoryId -> jdbc.update(
                "INSERT INTO catalog_product_category(product_id, category_id) VALUES (?, ?)", productId, categoryId.value()));
        jdbc.update("DELETE FROM catalog_product_attribute WHERE product_id = ?", productId);
        product.getAttributes().forEach((code, value) -> jdbc.update(
                "INSERT INTO catalog_product_attribute(product_id, attribute_code, value) VALUES (?, ?, ?)", productId, code, value));
    }

    private Product hydrate(Product stored) {
        UUID id = stored.getId().value();
        UUID brand = jdbc.query("SELECT brand_id FROM catalog_product WHERE id = ?", rs -> rs.next() ? rs.getObject(1, UUID.class) : null, id);
        var categoryIds = new LinkedHashSet<>(jdbc.query("SELECT category_id FROM catalog_product_category WHERE product_id = ?",
                (rs, row) -> new io.artio.catalog.CategoryId(rs.getObject(1, UUID.class)), id));
        var attributes = new LinkedHashMap<String, String>();
        jdbc.query("SELECT attribute_code, value FROM catalog_product_attribute WHERE product_id = ?", rs -> {
            while (rs.next()) attributes.put(rs.getString(1), rs.getString(2));
        }, id);
        var seo = jdbc.query("SELECT seo_slug,seo_title,seo_description FROM catalog_product WHERE id=? AND seo_slug IS NOT NULL",
                rs -> rs.next() ? new io.artio.catalog.ProductSeoMetadata(rs.getString(1), rs.getString(2), rs.getString(3)) : null, id);
        return Product.reconstitute(stored.getId(), stored.getName(), stored.getDescription(), stored.getSku(),
                stored.isActive(), stored.getCreatedAt(), stored.getUpdatedAt(),
                brand == null ? null : new io.artio.catalog.BrandId(brand), categoryIds, attributes, seo);
    }
}
