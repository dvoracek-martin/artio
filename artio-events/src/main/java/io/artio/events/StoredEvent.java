package io.artio.events;

import io.artio.kernel.AggregateEvent;
import java.util.Objects;

/** An event together with its position in an aggregate event stream. */
public record StoredEvent(String streamId, long sequence, AggregateEvent event) {
    public StoredEvent {
        Objects.requireNonNull(streamId, "Stream ID is required");
        Objects.requireNonNull(event, "Event is required");
        if (streamId.isBlank()) {
            throw new IllegalArgumentException("Stream ID must not be blank");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("Event sequence must be positive");
        }
    }
}