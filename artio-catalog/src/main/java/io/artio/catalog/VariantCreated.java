package io.artio.catalog;

import java.util.Map;

public record VariantCreated(VariantId variantId, ProductId productId, VariantName name, Sku sku,
                             Map<String, String> attributes) implements VariantEvent {
    public VariantCreated { attributes = Map.copyOf(attributes); }
    public VariantCreated(VariantId variantId, ProductId productId, VariantName name, Sku sku) {
        this(variantId, productId, name, sku, Map.of());
    }
}
