package io.artio.events;

import io.artio.kernel.AggregateEvent;
import java.util.List;

/** Append-only persistence port for aggregate event streams. */
public interface EventStore {
    long append(String streamId, long expectedVersion, List<? extends AggregateEvent> events);

    List<StoredEvent> load(String streamId, long afterSequence);
}