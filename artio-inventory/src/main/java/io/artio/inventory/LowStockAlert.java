package io.artio.inventory;

import java.math.BigDecimal;
import java.util.UUID;

public record LowStockAlert(UUID warehouseId, UUID sellableId, BigDecimal available, BigDecimal threshold) { }
