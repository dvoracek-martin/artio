package io.artio.inventory;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Per-warehouse reorder threshold for one sellable inventory item. */
public record LowStockPolicy(UUID id, WarehouseId warehouseId, UUID sellableId,
                             BigDecimal threshold, boolean active) {
    public LowStockPolicy {
        Objects.requireNonNull(id, "Policy id is required");
        Objects.requireNonNull(warehouseId, "Warehouse id is required");
        Objects.requireNonNull(sellableId, "Sellable id is required");
        Objects.requireNonNull(threshold, "Low stock threshold is required");
        if (threshold.signum() < 0 || threshold.precision() > 15 || threshold.stripTrailingZeros().scale() > 4)
            throw new IllegalArgumentException("Threshold must be non-negative with at most 15 digits and 4 decimal places");
    }
}
