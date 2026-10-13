package io.artio.orders;

import java.util.UUID;

public record CartId(UUID value) { public CartId { if (value == null) throw new IllegalArgumentException("Cart id required"); } }
