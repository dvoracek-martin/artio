package io.artio.catalog.ports;

import io.artio.catalog.Product;
import io.artio.catalog.ProductId;
import io.artio.catalog.Sku;
import java.util.List;
import java.util.Optional;

/** Persistence port for catalog products. Implementations belong outside the domain module. */
public interface ProductRepository {
    Optional<Product> findById(ProductId id);

    Optional<Product> findBySku(Sku sku);

    List<Product> findAll();


    void save(Product product);
}
