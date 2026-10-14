package io.artio.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class BackgroundWorkerConfigurationTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(BackgroundWorkerConfiguration.class);

    @Test
    void apiProcessDoesNotStartScheduledWork() {
        context.withPropertyValues("artio.workers.enabled=false", "artio.notifications.worker.enabled=false")
                .run(result -> assertThat(result).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @Test
    void eventWorkerEnablesScheduledWork() {
        context.withPropertyValues("artio.workers.enabled=true", "artio.notifications.worker.enabled=false")
                .run(result -> assertThat(result).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @Test
    void notificationWorkerEnablesScheduledDelivery() {
        context.withPropertyValues("artio.workers.enabled=false", "artio.notifications.worker.enabled=true")
                .run(result -> assertThat(result).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
    }
}
