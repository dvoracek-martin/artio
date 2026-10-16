package io.artio.api.catalog;

import io.artio.catalog.LocaleCode;
import io.artio.catalog.LocalizedProductContent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProductTranslationApplicationService {
    private final JdbcTemplate jdbc;
    private final ProductApplicationService products;
    private final ObjectProvider<io.artio.catalog.ports.ProductSearch> search;
    private final CatalogSearchIndexLock searchIndexLock;

    public ProductTranslationApplicationService(JdbcTemplate jdbc, ProductApplicationService products,
            ObjectProvider<io.artio.catalog.ports.ProductSearch> search, CatalogSearchIndexLock searchIndexLock) {
        this.jdbc = jdbc;
        this.products = products;
        this.search = search;
        this.searchIndexLock = searchIndexLock;
    }

    public TranslationView upsert(UUID productId, String locale, TranslationRequest request) {
        searchIndexLock.lockCatalogWrite();
        products.findById(productId);
        if (request == null) throw new IllegalArgumentException("Translation content is required");
        var content = new LocalizedProductContent(new LocaleCode(locale), request.name(), request.description());
        var seo = request.seoSlug() == null || request.seoSlug().isBlank() ? null
                : new io.artio.catalog.ProductSeoMetadata(request.seoSlug(), request.seoTitle(), request.seoDescription());
        if (seo == null && (hasText(request.seoTitle()) || hasText(request.seoDescription())))
            throw new IllegalArgumentException("A localized SEO title or description requires a canonical slug");
        jdbc.update("""
                INSERT INTO catalog_product_translations(product_id,locale,name,description,seo_slug,seo_title,seo_description,updated_at)
                VALUES (?,?,?,?,?,?,?,now())
                ON CONFLICT(product_id,locale) DO UPDATE SET name=EXCLUDED.name,description=EXCLUDED.description,
                    seo_slug=EXCLUDED.seo_slug,seo_title=EXCLUDED.seo_title,seo_description=EXCLUDED.seo_description,updated_at=now()
                """, productId, content.locale().value(), content.name(), content.description(),
                seo == null ? null : seo.slug(), seo == null ? null : seo.title(), seo == null ? null : seo.description());
        var product = products.findById(productId);
        var index = search.getIfAvailable();
        if (index != null) index.indexLocalized(productId, product.sku(), product.active(), content.locale(), content.name(), content.description());
        return new TranslationView(productId, content.locale().value(), content.name(), content.description(),
                seo == null ? null : seo.slug(), seo == null ? null : seo.title(), seo == null ? null : seo.description());
    }

    @Transactional(readOnly = true)
    public LocalizedProductView resolve(UUID productId, String locale) {
        ProductResponse product = products.findById(productId);
        LocaleCode requested = new LocaleCode(locale);
        Map<LocaleCode, LocalizedProductContent> translations = new LinkedHashMap<>();
        jdbc.query("SELECT locale,name,description FROM catalog_product_translations WHERE product_id=?",
                rs -> {
                    var code = new LocaleCode(rs.getString("locale"));
                    translations.put(code, new LocalizedProductContent(code, rs.getString("name"), rs.getString("description")));
                }, productId);
        var resolved = LocalizedProductContent.resolve(requested.value(), translations, product.name(), product.description());
        var seoRows = jdbc.query("SELECT locale,seo_slug,seo_title,seo_description FROM catalog_product_translations WHERE product_id=? AND seo_slug IS NOT NULL",
                (rs, row) -> new LocalizedSeo(rs.getString("locale"), rs.getString("seo_slug"), rs.getString("seo_title"), rs.getString("seo_description")), productId);
        var localizedSeo = seoRows.stream().filter(value -> value.locale().equals(requested.value())).findFirst()
                .or(() -> seoRows.stream().filter(value -> value.locale().equals("en")).findFirst()).orElse(null);
        return new LocalizedProductView(product.id(), product.sku(), product.active(), resolved.requestedLocale().value(), resolved.name(), resolved.description(),
                localizedSeo == null ? product.seoSlug() : localizedSeo.slug(),
                localizedSeo == null ? product.seoTitle() : localizedSeo.title(),
                localizedSeo == null ? product.seoDescription() : localizedSeo.description());
    }

    @Transactional(readOnly = true)
    public PublicProductView resolvePublishedBySlug(String slug, String locale) {
        if (slug == null || slug.isBlank()) throw new CatalogNotFoundException("Product not found");
        var requestedLocale = new LocaleCode(locale).value();
        UUID productId;
        try {
            productId = UUID.fromString(slug);
        } catch (IllegalArgumentException invalidProductId) {
            if (!slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
                throw new CatalogNotFoundException("Product not found");
            }
            productId = findPublishedProductIdBySlug(slug, requestedLocale)
                    .orElseThrow(() -> new CatalogNotFoundException("Product not found"));
        }
        var product = products.findPublishedById(productId);
        var localized = resolve(productId, requestedLocale);
        return new PublicProductView(product.id(), localized.name(), localized.description(), product.sku(),
                localized.seoSlug(), localized.seoTitle(), localized.seoDescription());
    }

    private java.util.Optional<UUID> findPublishedProductIdBySlug(String slug, String locale) {
        var localized = jdbc.query("""
                SELECT p.id FROM catalog_product_translations t
                JOIN catalog_product p ON p.id=t.product_id
                WHERE t.locale=? AND t.seo_slug=? AND p.active=TRUE AND p.archived_at IS NULL
                LIMIT 1
                """, (rs, row) -> rs.getObject(1, UUID.class), locale, slug).stream().findFirst();
        if (localized.isPresent()) return localized;
        if (!"en".equals(locale)) {
            localized = jdbc.query("""
                    SELECT p.id FROM catalog_product_translations t
                    JOIN catalog_product p ON p.id=t.product_id
                    WHERE t.locale='en' AND t.seo_slug=? AND p.active=TRUE AND p.archived_at IS NULL
                    LIMIT 1
                    """, (rs, row) -> rs.getObject(1, UUID.class), slug).stream().findFirst();
            if (localized.isPresent()) return localized;
        }
        return jdbc.query("""
                SELECT id FROM catalog_product
                WHERE seo_slug=? AND active=TRUE AND archived_at IS NULL
                LIMIT 1
                """, (rs, row) -> rs.getObject(1, UUID.class), slug).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<TranslationView> findAll(UUID productId) {
        products.findById(productId);
        return jdbc.query("SELECT locale,name,description,seo_slug,seo_title,seo_description FROM catalog_product_translations WHERE product_id=? ORDER BY locale",
                (rs, row) -> new TranslationView(productId, rs.getString("locale"), rs.getString("name"), rs.getString("description"),
                        rs.getString("seo_slug"), rs.getString("seo_title"), rs.getString("seo_description")), productId);
    }

    private record LocalizedSeo(String locale, String slug, String title, String description) { }
    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    public record TranslationRequest(String name, String description, String seoSlug, String seoTitle, String seoDescription) {
        public TranslationRequest(String name, String description) { this(name, description, null, null, null); }
    }
    public record TranslationView(UUID productId, String locale, String name, String description,
                                  String seoSlug, String seoTitle, String seoDescription) { }
    public record LocalizedProductView(UUID id, String sku, boolean active, String requestedLocale, String name,
                                       String description, String seoSlug, String seoTitle, String seoDescription) { }
    public record PublicProductView(UUID id, String name, String description, String sku,
                                    String seoSlug, String seoTitle, String seoDescription) { }
}
