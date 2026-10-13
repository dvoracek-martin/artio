package io.artio.catalog;

public record ProductDeactivated(ProductId productId) implements ProductEvent {
}
