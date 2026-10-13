package io.artio.events;

import io.artio.kernel.AggregateEvent;
import java.util.function.Consumer;

/** Publishes domain facts to interested subscribers. */
public interface DomainEventBus {
    void publish(AggregateEvent event);

    <E extends AggregateEvent> Subscription subscribe(Class<E> eventType, Consumer<? super E> subscriber);
}