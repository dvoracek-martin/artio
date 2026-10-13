package io.artio.catalog;
public record BrandActiveChanged(BrandId brandId, boolean active) implements BrandEvent {}
