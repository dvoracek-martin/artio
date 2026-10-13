package io.artio.events;

import java.util.Objects;

/** Serialized event payload with a stable type identifier. */
public record SerializedEvent(String type, byte[] payload) {
    public SerializedEvent {
        Objects.requireNonNull(type, "Event type is required");
        Objects.requireNonNull(payload, "Event payload is required");
        if (type.isBlank()) {
            throw new IllegalArgumentException("Event type must not be blank");
        }
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}