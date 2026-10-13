package io.artio.inventory;
import io.artio.kernel.ValueObject; import java.util.Objects; import java.util.UUID;
public record ReservationId(UUID value) implements ValueObject { public ReservationId { Objects.requireNonNull(value,"Reservation ID is required"); } }
