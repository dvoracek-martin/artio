package io.artio.persistence;

import java.util.Optional;

/** Persistence port for storing and retrieving aggregates. */
public interface Repository<T, ID> {
    Optional<T> findById(ID id);

    void save(T aggregate);

    void deleteById(ID id);
}