package io.artio.catalog;

import io.artio.kernel.AggregateEvent;

public sealed interface ProductEvent extends AggregateEvent
        permits ProductCreated, ProductActivated, ProductDeactivated, ProductRenamed, ProductDescriptionUpdated,
        ProductClassificationChanged, ProductAttributeChanged {
    ProductId productId();
}
