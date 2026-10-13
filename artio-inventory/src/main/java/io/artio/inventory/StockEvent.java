package io.artio.inventory;
import io.artio.kernel.AggregateEvent;
public sealed interface StockEvent extends AggregateEvent permits StockReceived,StockAdjusted,StockReserved,StockReservationReleased,StockAllocated { StockLevelId stockLevelId(); }
