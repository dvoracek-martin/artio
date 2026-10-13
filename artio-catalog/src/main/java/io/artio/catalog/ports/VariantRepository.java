package io.artio.catalog.ports;

import io.artio.catalog.ProductId;
import io.artio.catalog.Variant;
import io.artio.catalog.VariantId;
import java.util.List;
import java.util.Optional;

/** Persistence port for catalog variants. */
public interface VariantRepository {
    Optional<Variant> findById(VariantId id);

    List<Variant> findByProductId(ProductId productId);

    void save(Variant variant);
}