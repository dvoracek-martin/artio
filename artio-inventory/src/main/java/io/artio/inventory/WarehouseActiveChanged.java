package io.artio.inventory;
public record WarehouseActiveChanged(WarehouseId warehouseId,boolean active) implements WarehouseEvent {}
