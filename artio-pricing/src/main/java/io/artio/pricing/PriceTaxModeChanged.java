package io.artio.pricing;

public record PriceTaxModeChanged(PriceId priceId, boolean pricesIncludeTax) implements PriceEvent { }
