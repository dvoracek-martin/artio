package io.artio.inventory.postgres;
import io.artio.inventory.*; import io.artio.inventory.ports.StockRepository; import java.math.BigDecimal; import java.util.*; import org.springframework.jdbc.core.JdbcTemplate; import org.springframework.jdbc.core.RowMapper; import org.springframework.stereotype.Repository;
@Repository public class StockPostgresRepository implements StockRepository {
    private static final RowMapper<StockLevel> ROW=(r,n)->StockLevel.reconstitute(new StockLevelId(r.getObject("id",UUID.class)),new WarehouseId(r.getObject("warehouse_id",UUID.class)),r.getObject("sellable_id",UUID.class),r.getBigDecimal("on_hand"),r.getBigDecimal("reserved"));
    private final JdbcTemplate jdbc; public StockPostgresRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<StockLevel> findByWarehouseAndSellable(WarehouseId w,UUID item){return jdbc.query("SELECT * FROM inventory_stock_level WHERE warehouse_id=? AND sellable_id=?",ROW,w.value(),item).stream().findFirst();}
    public List<StockLevel> findBySellable(UUID item){return jdbc.query("SELECT * FROM inventory_stock_level WHERE sellable_id=? ORDER BY warehouse_id",ROW,item);}
    public List<StockLevel> findByWarehouse(WarehouseId warehouse){return jdbc.query("SELECT * FROM inventory_stock_level WHERE warehouse_id=? ORDER BY sellable_id",ROW,warehouse.value());}
    public StockLevel lockOrCreate(WarehouseId warehouse,UUID item){jdbc.update("INSERT INTO inventory_stock_level(id,warehouse_id,sellable_id,on_hand,reserved) VALUES (?,?,?,0,0) ON CONFLICT(warehouse_id,sellable_id) DO NOTHING",UUID.randomUUID(),warehouse.value(),item);return jdbc.query("SELECT * FROM inventory_stock_level WHERE warehouse_id=? AND sellable_id=? FOR UPDATE",ROW,warehouse.value(),item).stream().findFirst().orElseThrow(()->new IllegalStateException("Unable to lock stock level"));}
    public void save(StockLevel s){jdbc.update("UPDATE inventory_stock_level SET on_hand=?,reserved=? WHERE id=?",s.getOnHand(),s.getReserved(),s.getId().value());}
}
