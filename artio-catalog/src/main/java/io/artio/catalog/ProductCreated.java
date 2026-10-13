package io.artio.catalog;

public record ProductCreated(ProductId productId, ProductName name, ProductDescription description, Sku sku)
        implements ProductEvent {
}
