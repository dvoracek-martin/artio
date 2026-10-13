package io.artio.inventory;
import java.math.BigDecimal;
public record StockReceived(StockLevelId stockLevelId,BigDecimal quantity,BigDecimal onHandAfter) implements StockEvent {}
