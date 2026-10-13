package io.artio.persistence;

import java.util.function.Supplier;

/** Coordinates a group of operations that must commit or roll back together. */
@FunctionalInterface
public interface UnitOfWork {
    <T> T execute(Supplier<T> operation);
}