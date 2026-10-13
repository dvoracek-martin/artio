package io.artio.inventory;
import io.artio.kernel.ValueObject; import java.util.Objects; import java.util.UUID;
public record StockLevelId(UUID value) implements ValueObject { public StockLevelId { Objects.requireNonNull(value,"Stock level ID is required"); } }
