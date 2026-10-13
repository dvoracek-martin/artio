package io.artio.pricing;
public record PriceActiveChanged(PriceId priceId, boolean active) implements PriceEvent {}
