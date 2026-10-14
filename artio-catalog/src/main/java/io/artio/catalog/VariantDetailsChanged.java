package io.artio.catalog;

import java.util.Map;

public record VariantDetailsChanged(VariantId variantId, VariantName name, Sku sku,
                                    Map<String, String> attributes) implements VariantEvent {
    public VariantDetailsChanged { attributes = Map.copyOf(attributes); }
}
