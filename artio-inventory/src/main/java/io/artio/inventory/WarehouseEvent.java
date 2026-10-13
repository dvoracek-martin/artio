package io.artio.inventory;
import io.artio.kernel.AggregateEvent;
public sealed interface WarehouseEvent extends AggregateEvent permits WarehouseCreated,WarehouseUpdated,WarehouseActiveChanged { WarehouseId warehouseId(); }
