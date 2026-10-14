package io.artio.catalog.ports;

import io.artio.catalog.Product;
import io.artio.catalog.ProductId;
import io.artio.catalog.Sku;
import java.util.List;
import java.util.Optional;
import java.time.Instant;

/** Persistence port for catalog products. Implementations belong outside the domain module. */
public interface ProductRepository {
    Optional<Product> findById(ProductId id);

    Optional<Product> findBySku(Sku sku);

    List<Product> findAll();

    /** Locks and returns a bounded batch of due products for one scheduler instance. */
    List<Product> lockDueForPublication(Instant now, int limit);


    void save(Product product);
}
