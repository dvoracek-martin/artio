package io.artio.pricing;
import io.artio.kernel.AggregateEvent;
public sealed interface PriceEvent extends AggregateEvent permits PriceCreated, PriceAmountChanged, PriceActiveChanged { PriceId priceId(); }
