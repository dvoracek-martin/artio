package io.artio.kernel;

import java.math.BigDecimal;
import java.util.Objects;

/** A percentage value in the inclusive range from zero to one hundred. */
public record Percentage(BigDecimal value) implements ValueObject {
    private static final BigDecimal MINIMUM = BigDecimal.ZERO;
    private static final BigDecimal MAXIMUM = BigDecimal.valueOf(100);

    public Percentage {
        Objects.requireNonNull(value, "Percentage is required");
        if (value.compareTo(MINIMUM) < 0 || value.compareTo(MAXIMUM) > 0) {
            throw new IllegalArgumentException("Percentage must be between 0 and 100");
        }
    }
}