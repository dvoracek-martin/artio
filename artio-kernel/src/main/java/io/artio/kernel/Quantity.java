package io.artio.kernel;

import java.math.BigDecimal;
import java.util.Objects;

/** A non-negative amount of something, without imposing a unit system. */
public record Quantity(BigDecimal value) implements ValueObject {
    public Quantity {
        Objects.requireNonNull(value, "Quantity is required");
        if (value.signum() < 0) {
            throw new IllegalArgumentException("Quantity must not be negative");
        }
    }
}