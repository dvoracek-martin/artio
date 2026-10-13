package io.artio.catalog;
public record BrandUpdated(BrandId brandId, BrandName oldName, BrandName newName) implements BrandEvent {}
