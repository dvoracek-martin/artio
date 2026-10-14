package io.artio.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables scheduled outbox and maintenance workers only in the background runtime. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "artio.workers.enabled", havingValue = "true", matchIfMissing = true)
public class BackgroundWorkerConfiguration { }
