package io.artio.inventory.postgres;
import io.artio.inventory.*; import io.artio.inventory.ports.ReservationRepository; import java.util.*; import org.springframework.jdbc.core.JdbcTemplate; import org.springframework.jdbc.core.RowMapper; import org.springframework.stereotype.Repository;
@Repository public class ReservationPostgresRepository implements ReservationRepository {
    private static final RowMapper<Reservation> ROW=(r,n)->Reservation.reconstitute(new ReservationId(r.getObject("id",UUID.class)),r.getObject("order_id",UUID.class),new WarehouseId(r.getObject("warehouse_id",UUID.class)),r.getObject("sellable_id",UUID.class),r.getBigDecimal("quantity"),r.getTimestamp("expires_at").toInstant(),ReservationState.valueOf(r.getString("state")));
    private final JdbcTemplate jdbc; public ReservationPostgresRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<Reservation> findById(ReservationId id){return jdbc.query("SELECT * FROM inventory_reservation WHERE id=?",ROW,id.value()).stream().findFirst();}
    public Optional<Reservation> findByOrderAndWarehouseAndSellable(UUID order,WarehouseId warehouse,UUID item){return jdbc.query("SELECT * FROM inventory_reservation WHERE order_id=? AND warehouse_id=? AND sellable_id=?",ROW,order,warehouse.value(),item).stream().findFirst();}
    public List<Reservation> findByOrder(UUID order){return jdbc.query("SELECT * FROM inventory_reservation WHERE order_id=? ORDER BY warehouse_id,sellable_id",ROW,order);}
    public List<Reservation> findExpired(java.time.Instant now,int limit){return jdbc.query("SELECT * FROM inventory_reservation WHERE state='RESERVED' AND expires_at<=? ORDER BY expires_at LIMIT ?",ROW,java.sql.Timestamp.from(now),limit);}
    public void save(Reservation r){jdbc.update("INSERT INTO inventory_reservation(id,order_id,warehouse_id,sellable_id,quantity,state,expires_at) VALUES (?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET state=EXCLUDED.state,expires_at=EXCLUDED.expires_at",r.getId().value(),r.getOrderId(),r.getWarehouseId().value(),r.getSellableId(),r.getQuantity(),r.getState().name(),java.sql.Timestamp.from(r.getExpiresAt()));}
}
