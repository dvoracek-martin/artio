package io.artio.observability;

import java.util.Optional;

/** Starts spans and exposes the current span without binding the domain to an SDK. */
public interface Tracer {
    Span startSpan(String name, TraceContext parent);

    Optional<Span> currentSpan();
}