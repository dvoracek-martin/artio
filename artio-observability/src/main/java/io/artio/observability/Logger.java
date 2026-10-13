package io.artio.observability;

import java.util.Map;

/** Structured logging port for adapters to implement. */
public interface Logger {
    void debug(String message, Map<String, ?> attributes);

    void info(String message, Map<String, ?> attributes);

    void warn(String message, Map<String, ?> attributes);

    void error(String message, Map<String, ?> attributes, Throwable cause);
}