package io.artio.observability;

import java.time.Duration;
import java.util.Map;

/** Records application metrics without depending on a vendor-specific client. */
public interface MetricsRecorder {
    void incrementCounter(String name, Map<String, String> tags);

    void recordDuration(String name, Duration duration, Map<String, String> tags);
}