package io.artio.inventory;
import io.artio.kernel.ValueObject; import java.util.Objects; import java.util.UUID;
public record WarehouseId(UUID value) implements ValueObject { public WarehouseId { Objects.requireNonNull(value,"Warehouse ID is required"); } }
