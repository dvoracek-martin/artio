package io.artio.observability;

import java.util.Objects;

/** Vendor-neutral identifiers used to propagate a distributed trace. */
public record TraceContext(String traceId, String spanId, boolean sampled) {
    public TraceContext {
        Objects.requireNonNull(traceId, "Trace ID is required");
        Objects.requireNonNull(spanId, "Span ID is required");
        if (traceId.isBlank() || spanId.isBlank()) {
            throw new IllegalArgumentException("Trace and span IDs must not be blank");
        }
    }
}