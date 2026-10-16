package io.artio.api.catalog;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Bounded, filterable PostgreSQL-backed catalog query for Backoffice. */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/admin/catalog/products")
public class AdminCatalogProductQueryController {
    private final JdbcTemplate jdbc;
    public AdminCatalogProductQueryController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public ProductPage list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "ALL") String state,
            @RequestParam(required = false) UUID brandId, @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "updatedAt") String sort, @RequestParam(defaultValue = "desc") String direction) {
        if (page < 0 || page > 100_000 || size < 1 || size > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page must be non-negative and size must be 1–100");
        String search = q == null || q.isBlank() ? null : q.trim();
        if (search != null && search.length() > 200)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search query is too long");
        String normalizedState = switch (state.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "ALL", "ACTIVE", "INACTIVE", "ARCHIVED" -> state.trim().toUpperCase(java.util.Locale.ROOT);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "State must be ALL, ACTIVE, INACTIVE or ARCHIVED");
        };
        String sortColumn = switch (sort) {
            case "name" -> "p.name"; case "sku" -> "p.sku"; case "createdAt" -> "p.created_at"; case "updatedAt" -> "p.updated_at";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported product sort field");
        };
        String order = switch (direction.toLowerCase(java.util.Locale.ROOT)) {
            case "asc" -> "ASC"; case "desc" -> "DESC";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sort direction must be asc or desc");
        };
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (normalizedState.equals("ARCHIVED")) where.append(" AND p.archived_at IS NOT NULL");
        else {
            where.append(" AND p.archived_at IS NULL");
            if (!normalizedState.equals("ALL")) where.append(" AND p.active=").append(normalizedState.equals("ACTIVE") ? "TRUE" : "FALSE");
        }
        if (search != null) {
            where.append(" AND (p.name ILIKE ? ESCAPE '!' OR p.sku ILIKE ? ESCAPE '!' OR CAST(p.id AS text) ILIKE ? ESCAPE '!')");
            String pattern = "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            args.add(pattern); args.add(pattern); args.add(pattern);
        }
        if (brandId != null) { where.append(" AND p.brand_id=?"); args.add(brandId); }
        if (categoryId != null) { where.append(" AND EXISTS (SELECT 1 FROM catalog_product_category pc WHERE pc.product_id=p.id AND pc.category_id=?)"); args.add(categoryId); }
        long total = jdbc.queryForObject("SELECT count(*) FROM catalog_product p" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(size); pageArgs.add((long) page * size);
        List<ProductSummary> items = jdbc.query("SELECT p.id,p.name,p.description,p.sku,p.active,p.created_at,p.updated_at,p.brand_id,p.seo_slug,p.seo_title,p.seo_description,p.archived_at,p.scheduled_publish_at " +
                        "FROM catalog_product p" + where + " ORDER BY " + sortColumn + " " + order + ",p.id ASC LIMIT ? OFFSET ?",
                (rs, row) -> new ProductSummary(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"), rs.getString("sku"),
                        rs.getBoolean("active"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                        rs.getObject("brand_id", UUID.class), rs.getString("seo_slug"), rs.getString("seo_title"), rs.getString("seo_description"),
                        rs.getTimestamp("archived_at") != null,
                        rs.getTimestamp("scheduled_publish_at") == null ? null : rs.getTimestamp("scheduled_publish_at").toInstant()), pageArgs.toArray());
        return new ProductPage(items, total, page, size);
    }

    public record ProductSummary(UUID id, String name, String description, String sku, boolean active, Instant createdAt, Instant updatedAt,
            UUID brandId, String seoSlug, String seoTitle, String seoDescription, boolean archived, Instant scheduledPublishAt) { }
    public record ProductPage(List<ProductSummary> items, long total, int page, int size) { }
}
