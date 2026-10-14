package io.artio.api.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.artio.api.BackgroundWorkerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class LicenseRefreshSchedulerTests {
    @Test
    void configuredApiRefreshDoesNotEnableUnrelatedScheduledWorkersAndStopsWithContext() {
        LicensingService licensing = mock(LicensingService.class);
        when(licensing.refreshConfigured()).thenReturn(true);
        var observed = new java.util.concurrent.atomic.AtomicReference<LicenseRefreshScheduler>();
        new WebApplicationContextRunner().withBean(LicensingService.class, () -> licensing)
                .withUserConfiguration(LicenseRefreshScheduler.class, BackgroundWorkerConfiguration.class)
                .withPropertyValues("artio.runtime.role=api", "artio.workers.enabled=false", "artio.notifications.worker.enabled=false")
                .run(context -> {
                    var scheduler = context.getBean(LicenseRefreshScheduler.class);
                    observed.set(scheduler);
                    assertThat(scheduler.isRunning()).isTrue();
                    assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
                });
        assertThat(observed.get().isRunning()).isFalse();
    }

    @Test
    void nonWebWorkersNeverStartTheLicenseRefreshScheduler() {
        LicensingService licensing = mock(LicensingService.class);
        new ApplicationContextRunner().withBean(LicensingService.class, () -> licensing)
                .withUserConfiguration(LicenseRefreshScheduler.class).withPropertyValues("artio.runtime.role=api")
                .run(context -> assertThat(context).doesNotHaveBean(LicenseRefreshScheduler.class));
        verifyNoInteractions(licensing);
    }

    @Test
    void unconfiguredCommunityApiStartsNoLicenseRefreshThread() {
        LicensingService licensing = mock(LicensingService.class);
        new WebApplicationContextRunner().withBean(LicensingService.class, () -> licensing)
                .withUserConfiguration(LicenseRefreshScheduler.class)
                .run(context -> assertThat(context.getBean(LicenseRefreshScheduler.class).isRunning()).isFalse());
    }
}
