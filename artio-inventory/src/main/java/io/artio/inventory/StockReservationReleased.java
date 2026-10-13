package io.artio.inventory;
import java.math.BigDecimal;
public record StockReservationReleased(StockLevelId stockLevelId,BigDecimal quantity,BigDecimal availableAfter) implements StockEvent {}
