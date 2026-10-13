package io.artio.orders;

import io.artio.kernel.AggregateEvent;

public record CartItemAdded(CartId cartId, CartLineItem item) implements AggregateEvent { }
