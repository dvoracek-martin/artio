package io.artio.inventory.postgres;

import io.artio.inventory.StockAdjusted;
import io.artio.inventory.StockAllocated;
import io.artio.inventory.StockLevel;
import io.artio.inventory.StockLevelId;
import io.artio.inventory.StockMovement;
import io.artio.inventory.StockReceived;
import io.artio.inventory.StockReservationReleased;
import io.artio.inventory.StockReserved;
import io.artio.inventory.WarehouseId;
import io.artio.inventory.ports.StockRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class StockPostgresRepository implements StockRepository {
    private static final RowMapper<StockLevel> STOCK_ROW=(r,n)->StockLevel.reconstitute(
            new StockLevelId(r.getObject("id",UUID.class)),new WarehouseId(r.getObject("warehouse_id",UUID.class)),
            r.getObject("sellable_id",UUID.class),r.getBigDecimal("on_hand"),r.getBigDecimal("reserved"));
    private static final RowMapper<StockMovement> MOVEMENT_ROW=(r,n)->new StockMovement(
            r.getObject("id",UUID.class),r.getObject("warehouse_id",UUID.class),r.getObject("sellable_id",UUID.class),
            r.getString("movement_type"),r.getBigDecimal("on_hand_delta"),r.getBigDecimal("reserved_delta"),
            r.getBigDecimal("on_hand_after"),r.getBigDecimal("reserved_after"),r.getString("reason"),
            r.getObject("actor_id",UUID.class),r.getTimestamp("occurred_at").toInstant());
    private final JdbcTemplate jdbc;
    public StockPostgresRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @Override public Optional<StockLevel> findByWarehouseAndSellable(WarehouseId warehouse,UUID item){
        return jdbc.query("SELECT * FROM inventory_stock_level WHERE warehouse_id=? AND sellable_id=?",STOCK_ROW,warehouse.value(),item).stream().findFirst();
    }
    @Override public List<StockLevel> findBySellable(UUID item){return jdbc.query("SELECT * FROM inventory_stock_level WHERE sellable_id=? ORDER BY warehouse_id",STOCK_ROW,item);}
    @Override public List<StockLevel> findByWarehouse(WarehouseId warehouse){return jdbc.query("SELECT * FROM inventory_stock_level WHERE warehouse_id=? ORDER BY sellable_id",STOCK_ROW,warehouse.value());}
    @Override public List<StockMovement> findMovements(UUID warehouse,UUID item,int limit){
        return jdbc.query("SELECT * FROM inventory_stock_movement WHERE warehouse_id=? AND sellable_id=? ORDER BY occurred_at DESC,id DESC LIMIT ?",MOVEMENT_ROW,warehouse,item,limit);
    }
    @Override public StockLevel lockOrCreate(WarehouseId warehouse,UUID item){
        jdbc.update("INSERT INTO inventory_stock_level(id,warehouse_id,sellable_id,on_hand,reserved) VALUES (?,?,?,0,0) ON CONFLICT(warehouse_id,sellable_id) DO NOTHING",UUID.randomUUID(),warehouse.value(),item);
        return jdbc.query("SELECT * FROM inventory_stock_level WHERE warehouse_id=? AND sellable_id=? FOR UPDATE",STOCK_ROW,warehouse.value(),item).stream().findFirst().orElseThrow(()->new IllegalStateException("Unable to lock stock level"));
    }
    @Override public void save(StockLevel level){save(level,null,null);}
    @Override public void save(StockLevel level,String reason,UUID actorId){
        jdbc.update("UPDATE inventory_stock_level SET on_hand=?,reserved=? WHERE id=?",level.getOnHand(),level.getReserved(),level.getId().value());
        for(var event:level.getDomainEvents()){
            String type;BigDecimal onHandDelta=BigDecimal.ZERO,reservedDelta=BigDecimal.ZERO;
            if(event instanceof StockReceived received){type="RECEIPT";onHandDelta=received.quantity();}
            else if(event instanceof StockAdjusted adjusted){type="ADJUSTMENT";onHandDelta=adjusted.onHandAfter().subtract(adjusted.previousOnHand());}
            else if(event instanceof StockReserved reserved){type="RESERVATION";reservedDelta=reserved.quantity();}
            else if(event instanceof StockReservationReleased released){type="RELEASE";reservedDelta=released.quantity().negate();}
            else if(event instanceof StockAllocated allocated){type="ALLOCATION";onHandDelta=allocated.quantity().negate();reservedDelta=allocated.quantity().negate();}
            else continue;
            jdbc.update("INSERT INTO inventory_stock_movement(id,warehouse_id,sellable_id,movement_type,on_hand_delta,reserved_delta,on_hand_after,reserved_after,reason,actor_id) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(),level.getWarehouseId().value(),level.getSellableId(),type,onHandDelta,reservedDelta,level.getOnHand(),level.getReserved(),reason,actorId);
        }
        level.clearDomainEvents();
    }
}
