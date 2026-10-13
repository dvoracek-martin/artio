package io.artio.catalog;

public record ProductRenamed(ProductId productId, ProductName oldName, ProductName newName) implements ProductEvent {
}
