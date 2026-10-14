package io.artio.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables scheduled outbox and maintenance workers only in the background runtime. */
@Configuration
@EnableScheduling
@ConditionalOnExpression("'${artio.workers.enabled:true}' == 'true' or '${artio.notifications.worker.enabled:false}' == 'true'")
public class BackgroundWorkerConfiguration { }
