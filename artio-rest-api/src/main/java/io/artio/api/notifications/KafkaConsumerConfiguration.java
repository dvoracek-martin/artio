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
import org.springframework.jdbc.core.JdbcTemplate;
import io.artio.api.security.EncryptionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Bounded consumer retries route poison or persistently failing events to a dedicated Kafka topic. */
@Configuration
@ConditionalOnProperty(prefix = "artio.events.kafka", name = "consumer-enabled", havingValue = "true", matchIfMissing = true)
public class KafkaConsumerConfiguration {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory, KafkaTemplate<String, String> kafkaTemplate,
            JdbcTemplate jdbc, EncryptionService encryption) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, failure) -> new org.apache.kafka.common.TopicPartition(record.topic() + ".dlq", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(java.time.Duration.ofSeconds(10));
        org.springframework.kafka.listener.ConsumerRecordRecoverer persistableRecoverer = (record, failure) -> {
            recoverer.accept(record, failure);
            @SuppressWarnings("unchecked") ConsumerRecord<String, String> event = (ConsumerRecord<String, String>) record;
            jdbc.update("""
                    INSERT INTO kafka_consumer_dead_letters(id,source_topic,source_partition,source_offset,event_id,event_type,
                        record_key,encrypted_payload,payload_is_null,schema_version,error_class)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (source_topic,source_partition,source_offset) DO NOTHING
                    """, UUID.randomUUID(), event.topic(), event.partition(), event.offset(), header(event, "event-id"),
                    header(event, "event-type"), event.key(), event.value() == null ? null : encryption.encrypt(event.value()),
                    event.value() == null, header(event, "schema-version"),
                    failure.getClass().getSimpleName());
        };
        var errorHandler = new DefaultErrorHandler(persistableRecoverer, new FixedBackOff(1_000L, 5L));
        errorHandler.setAckAfterHandle(true);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
