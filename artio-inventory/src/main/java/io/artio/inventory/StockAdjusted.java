package io.artio.inventory;
import java.math.BigDecimal;
public record StockAdjusted(StockLevelId stockLevelId,BigDecimal previousOnHand,BigDecimal onHandAfter) implements StockEvent {}
