package io.artio.kernel;

/** A business rule that validates a candidate and rejects invalid state. */
@FunctionalInterface
public interface Invariant<T> {
    void validate(T candidate);
}
