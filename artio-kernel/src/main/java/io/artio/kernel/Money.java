package io.artio.kernel;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/** An immutable monetary amount with an explicit currency. */
public record Money(BigDecimal amount, Currency currency) implements ValueObject, java.io.Serializable {
    public Money {
        Objects.requireNonNull(amount, "Amount is required");
        Objects.requireNonNull(currency, "Currency is required");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }
}
