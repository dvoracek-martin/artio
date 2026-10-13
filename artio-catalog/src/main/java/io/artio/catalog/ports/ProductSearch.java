package io.artio.catalog.ports;
import io.artio.catalog.Product; import java.util.List;
/** Search index port, implemented by an external search adapter. */
public interface ProductSearch { void index(Product product); List<ProductSearchResult> search(String query, int limit); }
