package io.artio.catalog.ports;

import java.util.function.Function;

/** Search adapter that can atomically switch a fully replayed generation into service. */
public interface RebuildableProductSearch extends ProductSearch {
    <T> T rebuild(Function<ProductSearch, T> replay);
}
