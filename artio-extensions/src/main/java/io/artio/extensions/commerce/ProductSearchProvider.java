package io.artio.extensions.commerce;

import java.util.List;

/** Provider-neutral product search port; catalog reads remain available when no provider is active. */
public interface ProductSearchProvider {
    void index(ProductDocument product);
    void remove(String productId);
    SearchPage search(SearchRequest request);

    record ProductDocument(String productId, String sku, String name, String description,
                           String category, String brand, String locale) { }
    record SearchRequest(String query, String locale, int page, int size) { }
    record SearchPage(List<String> productIds, long total, int page, int size) {
        public SearchPage { productIds = productIds == null ? List.of() : List.copyOf(productIds); }
    }
}
