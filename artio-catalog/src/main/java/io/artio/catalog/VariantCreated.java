package io.artio.catalog;

public record VariantCreated(VariantId variantId, ProductId productId, VariantName name, Sku sku)
        implements VariantEvent {
}