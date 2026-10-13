package io.artio.orders;

import io.artio.kernel.AggregateEvent;
import java.util.UUID;

public record OrderCreated(OrderId orderId, UUID userId) implements AggregateEvent { }
