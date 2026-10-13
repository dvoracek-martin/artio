package io.artio.orders;

import io.artio.kernel.Money;
import io.artio.kernel.Quantity;
import java.util.UUID;

public record CartLineItem(UUID variantId, Quantity quantity, Money unitPrice) {
    public CartLineItem { if (variantId == null || quantity == null || unitPrice == null || quantity.value().signum() <= 0) throw new IllegalArgumentException("Cart item fields are required and quantity must be positive"); }
}
