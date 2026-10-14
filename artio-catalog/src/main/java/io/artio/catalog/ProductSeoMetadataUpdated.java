package io.artio.catalog;

public record ProductSeoMetadataUpdated(ProductId productId, ProductSeoMetadata previous, ProductSeoMetadata current)
        implements ProductEvent { }
