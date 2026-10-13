package io.artio.catalog;

import io.artio.kernel.AggregateEvent;

public sealed interface VariantEvent extends AggregateEvent
        permits VariantCreated, VariantActivated, VariantDeactivated {
    VariantId variantId();
}