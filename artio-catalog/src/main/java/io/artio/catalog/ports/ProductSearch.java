package io.artio.catalog.ports;
import io.artio.catalog.LocaleCode; import io.artio.catalog.Product; import io.artio.catalog.ports.ProductSearchResult; import java.util.List; import java.util.UUID;
/** Search index port, implemented by an external search adapter. */
public interface ProductSearch {
    void index(Product product);
    List<ProductSearchResult> search(String query, int limit);

    default void indexLocalized(UUID productId, String sku, boolean active, LocaleCode locale,
                                String name, String description) { }

    default List<ProductSearchResult> search(String query, int limit, LocaleCode locale) {
        return search(query, limit);
    }
}
