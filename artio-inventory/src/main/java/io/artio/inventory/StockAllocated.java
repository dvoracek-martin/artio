package io.artio.inventory;
import java.math.BigDecimal;
public record StockAllocated(StockLevelId stockLevelId,BigDecimal quantity,BigDecimal availableAfter) implements StockEvent {}
