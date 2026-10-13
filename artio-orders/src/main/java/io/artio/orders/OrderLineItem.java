package io.artio.orders;

import io.artio.kernel.Money;
import io.artio.kernel.Quantity;
import java.util.UUID;

public record OrderLineItem(UUID variantId, Quantity quantity, Money unitPrice, UUID bundleId) {
    public OrderLineItem(UUID variantId, Quantity quantity, Money unitPrice) { this(variantId, quantity, unitPrice, null); }
}
