package io.artio.events;

import io.artio.kernel.AggregateEvent;

/** Converts domain events to and from a transport or storage representation. */
public interface EventSerializer {
    SerializedEvent serialize(AggregateEvent event);

    AggregateEvent deserialize(SerializedEvent serializedEvent);
}