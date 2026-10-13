package io.artio.catalog;
public record ProductAttributeChanged(ProductId productId, String code, String value) implements ProductEvent {}
