package io.artio.orders;

import java.util.UUID;

public record OrderId(UUID value) { public OrderId { if (value == null) throw new IllegalArgumentException("Order id required"); } }
