package io.artio.inventory;
import java.math.BigDecimal; import java.util.UUID;
public record ReservationCreated(ReservationId reservationId,UUID orderId,WarehouseId warehouseId,UUID sellableId,BigDecimal quantity) implements ReservationEvent {}
