package io.artio.pricing;
import io.artio.kernel.Money; import java.util.UUID;
public record PriceCreated(PriceId priceId, UUID sellableId, Money amount, boolean pricesIncludeTax) implements PriceEvent {}
