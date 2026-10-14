package io.artio.inventory.ports;

import io.artio.inventory.LowStockPolicy;
import io.artio.inventory.LowStockAlert;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LowStockPolicyRepository {
    Optional<LowStockPolicy> findByWarehouseAndSellable(UUID warehouseId, UUID sellableId);
    List<LowStockPolicy> findAll();
    List<LowStockPolicy> findByWarehouse(UUID warehouseId);
    List<LowStockAlert> findLowStock();
    void save(LowStockPolicy policy);
}
