package io.artio.api.extensions;

import java.time.Duration;
import java.util.function.LongSupplier;

/** Small per-process breaker. A failed half-open probe immediately reopens it for the cooldown. */
final class ExtensionCircuitBreaker {
    private final int failureThreshold;
    private final long cooldownNanos;
    private final LongSupplier clock;
    private int consecutiveFailures;
    private long openUntilNanos;
    private boolean halfOpenProbeInFlight;

    ExtensionCircuitBreaker(int failureThreshold, Duration cooldown) {
        this(failureThreshold, cooldown, System::nanoTime);
    }

    ExtensionCircuitBreaker(int failureThreshold, Duration cooldown, LongSupplier clock) {
        if (failureThreshold < 1 || cooldown.isNegative() || cooldown.isZero()) throw new IllegalArgumentException("Invalid circuit-breaker policy");
        this.failureThreshold = failureThreshold;
        this.cooldownNanos = cooldown.toNanos();
        this.clock = clock;
    }

    synchronized void acquire() {
        if (openUntilNanos == 0) return;
        if (clock.getAsLong() - openUntilNanos < 0 || halfOpenProbeInFlight) throw new CircuitOpenException();
        halfOpenProbeInFlight = true;
    }

    synchronized void succeeded() {
        consecutiveFailures = 0;
        openUntilNanos = 0;
        halfOpenProbeInFlight = false;
    }

    synchronized void failed() {
        halfOpenProbeInFlight = false;
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) openUntilNanos = clock.getAsLong() + cooldownNanos;
    }

    static final class CircuitOpenException extends io.artio.extensions.ExtensionRuntimePort.ExtensionUnavailableException {
        CircuitOpenException() { super("Extension circuit is open"); }
    }
}
