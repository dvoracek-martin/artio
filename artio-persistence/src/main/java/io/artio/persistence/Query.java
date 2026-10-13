package io.artio.persistence;

import java.util.List;

/** A composable query port; implementations define how criteria map to storage. */
public interface Query<T> {
    Query<T> where(String property, Object value);

    Query<T> orderBy(String property, Direction direction);

    Query<T> limit(int maximumResults);

    List<T> execute();

    enum Direction {
        ASCENDING,
        DESCENDING
    }
}