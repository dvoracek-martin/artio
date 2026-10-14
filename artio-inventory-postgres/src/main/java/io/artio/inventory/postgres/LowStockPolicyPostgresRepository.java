package io.artio.inventory.postgres;

import io.artio.inventory.LowStockPolicy;
import io.artio.inventory.WarehouseId;
import io.artio.inventory.ports.LowStockPolicyRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LowStockPolicyPostgresRepository implements LowStockPolicyRepository {
    private final JdbcTemplate jdbc;

    public LowStockPolicyPostgresRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Optional<LowStockPolicy> findByWarehouseAndSellable(UUID warehouseId, UUID sellableId) {
        return jdbc.query("SELECT * FROM inventory_low_stock_policy WHERE warehouse_id=? AND sellable_id=?",
                (r,n) -> new LowStockPolicy(r.getObject("id", UUID.class), new WarehouseId(r.getObject("warehouse_id", UUID.class)),
                        r.getObject("sellable_id", UUID.class), r.getBigDecimal("threshold_quantity"), r.getBoolean("active")),
                warehouseId, sellableId).stream().findFirst();
    }

    @Override public List<LowStockPolicy> findAll() {
        return jdbc.query("SELECT * FROM inventory_low_stock_policy ORDER BY warehouse_id,sellable_id",
                (r,n) -> new LowStockPolicy(r.getObject("id", UUID.class), new WarehouseId(r.getObject("warehouse_id", UUID.class)),
                        r.getObject("sellable_id", UUID.class), r.getBigDecimal("threshold_quantity"), r.getBoolean("active")));
    }

    @Override public List<LowStockPolicy> findByWarehouse(UUID warehouseId) {
        return jdbc.query("SELECT * FROM inventory_low_stock_policy WHERE warehouse_id=? ORDER BY sellable_id",
                (r,n) -> new LowStockPolicy(r.getObject("id", UUID.class), new WarehouseId(r.getObject("warehouse_id", UUID.class)),
                        r.getObject("sellable_id", UUID.class), r.getBigDecimal("threshold_quantity"), r.getBoolean("active")), warehouseId);
    }

    @Override public List<io.artio.inventory.LowStockAlert> findLowStock() {
        return jdbc.query("SELECT p.warehouse_id,p.sellable_id,COALESCE(s.on_hand-s.reserved,0) AS available,p.threshold_quantity " +
                        "FROM inventory_low_stock_policy p LEFT JOIN inventory_stock_level s " +
                        "ON s.warehouse_id=p.warehouse_id AND s.sellable_id=p.sellable_id " +
                        "WHERE p.active=TRUE AND COALESCE(s.on_hand-s.reserved,0)<=p.threshold_quantity ORDER BY p.warehouse_id,p.sellable_id",
                (r,n) -> new io.artio.inventory.LowStockAlert(r.getObject("warehouse_id", UUID.class), r.getObject("sellable_id", UUID.class),
                        r.getBigDecimal("available"), r.getBigDecimal("threshold_quantity")));
    }

    @Override public void save(LowStockPolicy policy) {
        jdbc.update("INSERT INTO inventory_low_stock_policy(id,warehouse_id,sellable_id,threshold_quantity,active) VALUES (?,?,?,?,?) " +
                        "ON CONFLICT(warehouse_id,sellable_id) DO UPDATE SET threshold_quantity=EXCLUDED.threshold_quantity,active=EXCLUDED.active,updated_at=now()",
                policy.id(), policy.warehouseId().value(), policy.sellableId(), policy.threshold(), policy.active());
    }
}
