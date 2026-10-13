package io.artio.inventory;
import io.artio.kernel.Aggregate; import io.artio.kernel.Quantity; import java.math.BigDecimal; import java.util.Objects; import java.util.UUID;
/** On-hand and reserved stock for one sellable at one warehouse. */
public final class StockLevel extends Aggregate<StockLevelId> {
    private final WarehouseId warehouseId; private final UUID sellableId; private BigDecimal onHand; private BigDecimal reserved;
    private StockLevel(StockLevelId id,WarehouseId warehouseId,UUID sellableId,BigDecimal onHand,BigDecimal reserved){super(id);this.warehouseId=Objects.requireNonNull(warehouseId);this.sellableId=Objects.requireNonNull(sellableId);this.onHand=valid(onHand);this.reserved=valid(reserved);if(this.reserved.compareTo(this.onHand)>0)throw new IllegalArgumentException("Reserved stock cannot exceed on-hand stock");}
    public static StockLevel create(StockLevelId id,WarehouseId warehouseId,UUID sellableId){return new StockLevel(id,warehouseId,sellableId,BigDecimal.ZERO,BigDecimal.ZERO);}
    public static StockLevel reconstitute(StockLevelId id,WarehouseId warehouseId,UUID sellableId,BigDecimal onHand,BigDecimal reserved){return new StockLevel(id,warehouseId,sellableId,onHand,reserved);}
    public void receive(Quantity amount){BigDecimal value=positive(amount);onHand=valid(onHand.add(value));recordEvent(new StockReceived(getId(),value,onHand));}
    public void adjustTo(Quantity amount){BigDecimal value=nonNegative(amount.value());if(value.compareTo(reserved)<0)throw new IllegalStateException("On-hand stock cannot be adjusted below reserved quantity");if(value.compareTo(onHand)!=0){BigDecimal previous=onHand;onHand=value;recordEvent(new StockAdjusted(getId(),previous,value));}}
    public void reserve(Quantity amount){BigDecimal value=positive(amount);if(available().compareTo(value)<0)throw new IllegalStateException("Insufficient available stock");reserved=valid(reserved.add(value));recordEvent(new StockReserved(getId(),value,available()));}
    public void release(Quantity amount){BigDecimal value=positive(amount);if(reserved.compareTo(value)<0)throw new IllegalStateException("Cannot release more stock than is reserved");reserved=valid(reserved.subtract(value));recordEvent(new StockReservationReleased(getId(),value,available()));}
    public void allocate(Quantity amount){BigDecimal value=positive(amount);if(reserved.compareTo(value)<0)throw new IllegalStateException("Cannot allocate more stock than is reserved");reserved=valid(reserved.subtract(value));onHand=valid(onHand.subtract(value));recordEvent(new StockAllocated(getId(),value,available()));}
    public BigDecimal available(){return onHand.subtract(reserved);} public WarehouseId getWarehouseId(){return warehouseId;} public UUID getSellableId(){return sellableId;} public BigDecimal getOnHand(){return onHand;} public BigDecimal getReserved(){return reserved;}
    private static BigDecimal positive(Quantity q){Objects.requireNonNull(q);BigDecimal v=valid(q.value());if(v.signum()<=0)throw new IllegalArgumentException("Stock quantity must be greater than zero");return v;}
    private static BigDecimal nonNegative(BigDecimal v){v=valid(v);if(v.signum()<0)throw new IllegalArgumentException("Stock quantity cannot be negative");return v;}
    private static BigDecimal valid(BigDecimal v){Objects.requireNonNull(v,"Stock quantity is required");if(v.signum()<0||v.stripTrailingZeros().scale()>4||v.precision()>15)throw new IllegalArgumentException("Stock quantity must be non-negative with at most 15 digits and 4 decimal places");return v;}
}
