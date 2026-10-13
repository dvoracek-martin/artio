package io.artio.observability;

import java.util.Map;
import java.util.Objects;

/** Immutable message and attributes ready for a structured logging adapter. */
public record StructuredLog(String message, Map<String, ?> attributes) {
    public StructuredLog {
        Objects.requireNonNull(message, "Log message is required");
        Objects.requireNonNull(attributes, "Log attributes are required");
        attributes = Map.copyOf(attributes);
    }
}