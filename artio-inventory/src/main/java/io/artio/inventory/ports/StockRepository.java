package io.artio.inventory.ports;
import io.artio.inventory.StockLevel; import io.artio.inventory.WarehouseId; import java.util.List; import java.util.Optional; import java.util.UUID;
public interface StockRepository { Optional<StockLevel> findByWarehouseAndSellable(WarehouseId warehouseId,UUID sellableId); List<StockLevel> findBySellable(UUID sellableId); List<StockLevel> findByWarehouse(WarehouseId warehouseId); StockLevel lockOrCreate(WarehouseId warehouseId,UUID sellableId); void save(StockLevel stock); }
