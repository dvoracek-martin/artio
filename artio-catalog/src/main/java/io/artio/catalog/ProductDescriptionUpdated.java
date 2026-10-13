package io.artio.catalog;

public record ProductDescriptionUpdated(ProductId productId, ProductDescription oldDescription,
                                        ProductDescription newDescription) implements ProductEvent {
}
