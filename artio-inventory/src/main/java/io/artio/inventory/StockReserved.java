package io.artio.inventory;
import java.math.BigDecimal;
public record StockReserved(StockLevelId stockLevelId,BigDecimal quantity,BigDecimal availableAfter) implements StockEvent {}
