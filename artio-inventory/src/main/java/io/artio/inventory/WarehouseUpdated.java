package io.artio.inventory;
public record WarehouseUpdated(WarehouseId warehouseId,String name,String location) implements WarehouseEvent {}
