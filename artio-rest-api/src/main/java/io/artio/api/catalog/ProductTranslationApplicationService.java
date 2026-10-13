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
        var content = new LocalizedProductContent(new LocaleCode(locale), request.name(), request.description());
        jdbc.update("""
                INSERT INTO catalog_product_translations(product_id,locale,name,description,updated_at)
                VALUES (?,?,?,?,now())
                ON CONFLICT(product_id,locale) DO UPDATE SET name=EXCLUDED.name,description=EXCLUDED.description,updated_at=now()
                """, productId, content.locale().value(), content.name(), content.description());
        var product = products.findById(productId);
        var index = search.getIfAvailable();
        if (index != null) index.indexLocalized(productId, product.sku(), product.active(), content.locale(), content.name(), content.description());
        return new TranslationView(productId, content.locale().value(), content.name(), content.description());
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
        return new LocalizedProductView(product.id(), product.sku(), product.active(), resolved.requestedLocale().value(), resolved.name(), resolved.description());
    }

    @Transactional(readOnly = true)
    public List<TranslationView> findAll(UUID productId) {
        products.findById(productId);
        return jdbc.query("SELECT locale,name,description FROM catalog_product_translations WHERE product_id=? ORDER BY locale",
                (rs, row) -> new TranslationView(productId, rs.getString("locale"), rs.getString("name"), rs.getString("description")), productId);
    }

    public record TranslationRequest(String name, String description) { }
    public record TranslationView(UUID productId, String locale, String name, String description) { }
    public record LocalizedProductView(UUID id, String sku, boolean active, String requestedLocale, String name, String description) { }
}
