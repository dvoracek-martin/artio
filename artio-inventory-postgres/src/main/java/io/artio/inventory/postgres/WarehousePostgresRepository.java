package io.artio.inventory.postgres;
import io.artio.inventory.*; import io.artio.inventory.ports.WarehouseRepository; import java.util.*; import org.springframework.jdbc.core.JdbcTemplate; import org.springframework.jdbc.core.RowMapper; import org.springframework.stereotype.Repository;
@Repository public class WarehousePostgresRepository implements WarehouseRepository {
    private static final RowMapper<Warehouse> ROW=(r,n)->Warehouse.reconstitute(new WarehouseId(r.getObject("id",UUID.class)),r.getString("code"),r.getString("name"),r.getString("location"),r.getBoolean("active"));
    private final JdbcTemplate jdbc; public WarehousePostgresRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<Warehouse> findById(WarehouseId id){return jdbc.query("SELECT * FROM inventory_warehouse WHERE id=?",ROW,id.value()).stream().findFirst();}
    public List<Warehouse> findAll(){return jdbc.query("SELECT * FROM inventory_warehouse ORDER BY code",ROW);}
    public void save(Warehouse w){jdbc.update("INSERT INTO inventory_warehouse(id,code,name,location,active) VALUES (?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=EXCLUDED.name,location=EXCLUDED.location,active=EXCLUDED.active",w.getId().value(),w.getCode(),w.getName(),w.getLocation(),w.isActive());}
}
