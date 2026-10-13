package io.artio.inventory.ports;
import io.artio.inventory.Warehouse; import io.artio.inventory.WarehouseId; import java.util.List; import java.util.Optional;
public interface WarehouseRepository { Optional<Warehouse> findById(WarehouseId id); List<Warehouse> findAll(); void save(Warehouse warehouse); }
