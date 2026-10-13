package io.artio.pricing;
import io.artio.kernel.Money;
public record PriceAmountChanged(PriceId priceId, Money previousAmount, Money newAmount) implements PriceEvent {}
