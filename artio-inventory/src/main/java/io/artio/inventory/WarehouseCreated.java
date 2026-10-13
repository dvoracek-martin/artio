package io.artio.inventory;
public record WarehouseCreated(WarehouseId warehouseId,String code,String name) implements WarehouseEvent {}
