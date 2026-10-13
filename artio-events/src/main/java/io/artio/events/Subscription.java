package io.artio.events;

/** A cancellable event subscription. */
@FunctionalInterface
public interface Subscription extends AutoCloseable {
    @Override
    void close();
}