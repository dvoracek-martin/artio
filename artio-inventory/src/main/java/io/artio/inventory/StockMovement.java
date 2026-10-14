package io.artio.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Durable, append-only record of an on-hand or reservation quantity transition. */
public record StockMovement(UUID id, UUID warehouseId, UUID sellableId, String type,
        BigDecimal onHandDelta, BigDecimal reservedDelta, BigDecimal onHandAfter,
        BigDecimal reservedAfter, String reason, UUID actorId, Instant occurredAt) { }
