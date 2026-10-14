package io.artio.api.notifications;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** Bounded consumer retries route poison or persistently failing events to a dedicated Kafka topic. */
@Configuration
@ConditionalOnProperty(prefix = "artio.events.kafka", name = "consumer-enabled", havingValue = "true", matchIfMissing = true)
public class KafkaConsumerConfiguration {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory, KafkaTemplate<String, String> kafkaTemplate) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, failure) -> new org.apache.kafka.common.TopicPartition(record.topic() + ".dlq", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(java.time.Duration.ofSeconds(10));
        var errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 5L));
        errorHandler.setAckAfterHandle(true);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
