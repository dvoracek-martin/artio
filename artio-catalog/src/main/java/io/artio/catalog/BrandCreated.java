package io.artio.catalog;
public record BrandCreated(BrandId brandId, BrandName name) implements BrandEvent {}
