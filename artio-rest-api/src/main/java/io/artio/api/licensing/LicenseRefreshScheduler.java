package io.artio.api.licensing;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Dedicated scheduler does not enable unrelated API background workers through @EnableScheduling. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "artio.runtime.role", havingValue = "api", matchIfMissing = true)
final class LicenseRefreshScheduler implements SmartLifecycle {
    private final LicensingService licensing;
    private final long intervalSeconds;
    private ScheduledExecutorService executor;

    LicenseRefreshScheduler(LicensingService licensing,
            @Value("${artio.licensing.refresh-interval-seconds:21600}") long intervalSeconds) {
        this.licensing = licensing;
        if (intervalSeconds < 60 || intervalSeconds > 86400) throw new IllegalArgumentException("License refresh interval must be between 60 and 86400 seconds");
        this.intervalSeconds = intervalSeconds;
    }

    @Override public synchronized void start() {
        if (executor != null || !licensing.refreshConfigured()) return;
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "artio-license-refresh");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(licensing::refresh, 60, intervalSeconds, TimeUnit.SECONDS);
    }
    @Override public synchronized void stop() {
        if (executor != null) { executor.shutdownNow(); executor = null; }
    }
    @Override public synchronized boolean isRunning() { return executor != null && !executor.isShutdown(); }
}
