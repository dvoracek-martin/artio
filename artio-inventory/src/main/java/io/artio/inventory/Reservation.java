package io.artio.inventory;
import io.artio.kernel.Aggregate; import io.artio.kernel.Quantity; import java.math.BigDecimal; import java.util.Objects; import java.util.UUID;
/** Order stock commitment with a single legal transition out of RESERVED. */
public final class Reservation extends Aggregate<ReservationId> {
    private final UUID orderId; private final WarehouseId warehouseId; private final UUID sellableId; private final BigDecimal quantity; private ReservationState state;
    private Reservation(ReservationId id,UUID orderId,WarehouseId warehouseId,UUID sellableId,BigDecimal quantity,ReservationState state){super(id);this.orderId=Objects.requireNonNull(orderId);this.warehouseId=Objects.requireNonNull(warehouseId);this.sellableId=Objects.requireNonNull(sellableId);this.quantity=Objects.requireNonNull(quantity);if(quantity.signum()<=0||quantity.stripTrailingZeros().scale()>4||quantity.precision()>15)throw new IllegalArgumentException("Reservation quantity must be positive, with at most 15 digits and 4 decimal places");this.state=Objects.requireNonNull(state);}
    public static Reservation reserve(ReservationId id,UUID orderId,WarehouseId warehouseId,UUID sellableId,Quantity quantity){var reservation=new Reservation(id,orderId,warehouseId,sellableId,quantity.value(),ReservationState.RESERVED);reservation.recordEvent(new ReservationCreated(id,orderId,warehouseId,sellableId,quantity.value()));return reservation;}
    public static Reservation reconstitute(ReservationId id,UUID orderId,WarehouseId warehouseId,UUID sellableId,BigDecimal quantity,ReservationState state){return new Reservation(id,orderId,warehouseId,sellableId,quantity,state);}
    public void allocate(){transition(ReservationState.ALLOCATED);} public void release(){transition(ReservationState.RELEASED);}
    private void transition(ReservationState next){if(state!=ReservationState.RESERVED)throw new IllegalStateException("Only a reserved stock commitment can be allocated or released");state=next;recordEvent(new ReservationStateChanged(getId(),next));}
    public UUID getOrderId(){return orderId;} public WarehouseId getWarehouseId(){return warehouseId;} public UUID getSellableId(){return sellableId;} public BigDecimal getQuantity(){return quantity;} public ReservationState getState(){return state;}
}
