package io.artio.api.catalog;

import io.artio.catalog.LocaleCode;
import io.artio.catalog.ports.ProductRepository;
import io.artio.catalog.ports.ProductSearch;
import io.artio.catalog.ports.RebuildableProductSearch;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rebuilds the search documents from the authoritative PostgreSQL catalog. */
@Service
public class CatalogSearchReindexService {
    private final ProductRepository products;
    private final ObjectProvider<ProductSearch> searchProvider;
    private final JdbcTemplate jdbc;

    public CatalogSearchReindexService(ProductRepository products,
            ObjectProvider<ProductSearch> searchProvider, JdbcTemplate jdbc) {
        this.products = products;
        this.searchProvider = searchProvider;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public ReindexResult reindex() {
        ProductSearch search = searchProvider.getIfAvailable();
        if (search == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Product search is not enabled");

        if (search instanceof RebuildableProductSearch rebuildable) {
            return rebuildable.rebuild(this::indexCatalog);
        }
        return indexCatalog(search);
    }

    private ReindexResult indexCatalog(ProductSearch search) {
        int productCount = 0;
        int translationCount = 0;
        for (var product : products.findAll()) {
            search.index(product);
            productCount++;
            UUID productId = product.getId().value();
            translationCount += jdbc.query("SELECT locale,name,description FROM catalog_product_translations WHERE product_id=? ORDER BY locale",
                    rs -> {
                        int indexed = 0;
                        while (rs.next()) {
                            search.indexLocalized(productId, product.getSku().value(), product.isActive(),
                                    new LocaleCode(rs.getString("locale")), rs.getString("name"),
                                    rs.getString("description"));
                            indexed++;
                        }
                        return indexed;
                    }, productId);
        }
        return new ReindexResult(productCount, translationCount);
    }

    public record ReindexResult(int productsIndexed, int translationsIndexed) { }
}
