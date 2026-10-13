package io.artio.observability;

/** A vendor-neutral unit of work within a distributed trace. */
public interface Span extends AutoCloseable {
    TraceContext context();

    void setAttribute(String name, String value);

    void recordException(Throwable exception);

    @Override
    void close();
}