package io.artio.api.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Deduplicates order events and creates notification delivery work in the same PostgreSQL transaction. */
@Component
@ConditionalOnProperty(prefix = "artio.events.kafka", name = "consumer-enabled", havingValue = "true", matchIfMissing = true)
public class OrderCreatedNotificationConsumer {
    private static final String CONSUMER_NAME = "order-notification-v1";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OrderCreatedNotificationConsumer(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "${artio.events.kafka.order-topic:artio.order.events}",
            groupId = "${artio.events.kafka.notification-consumer-group:artio-notification-worker}",
            concurrency = "${artio.events.kafka.consumer-concurrency:1}")
    @Transactional
    public void consume(ConsumerRecord<String, String> record) {
        try {
            JsonNode event = mapper.readTree(record.value());
            UUID eventId = UUID.fromString(event.path("eventId").asText());
            UUID orderId = UUID.fromString(event.path("orderId").asText());
            UUID userId = UUID.fromString(event.path("userId").asText());
            int schemaVersion = event.path("schemaVersion").asInt(-1);
            String eventType = event.path("type").asText();
            String locale = event.path("locale").asText();
            java.time.Instant.parse(event.path("occurredAt").asText());

            if (!event.isObject() || !event.path("schemaVersion").isIntegralNumber() || schemaVersion != 1
                    || !event.path("type").isTextual() || !event.path("locale").isTextual()
                    || !event.path("occurredAt").isTextual() || !"ORDER_CREATED".equals(eventType)
                    || !eventId.toString().equals(requiredHeader(record, "event-id"))
                    || !eventType.equals(requiredHeader(record, "event-type"))
                    || !"1".equals(requiredHeader(record, "schema-version"))
                    || !locale.matches("[a-z]{2}(-[A-Z]{2})?")
                    || !orderId.toString().equals(record.key())) {
                throw new IllegalArgumentException("Order event envelope is invalid or unsupported");
            }

            int received = jdbc.update("""
                    INSERT INTO event_inbox(consumer_name,event_id,event_type,schema_version,aggregate_id)
                    VALUES (?,?,?,?,?) ON CONFLICT (consumer_name,event_id) DO NOTHING
                    """, CONSUMER_NAME, eventId, eventType, schemaVersion, orderId.toString());
            if (received == 0) return;

            Boolean canEmail = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id=? AND active=true AND email NOT LIKE '%@privacy.artio.invalid')",
                    Boolean.class, userId);
            if (Boolean.TRUE.equals(canEmail)) {
                var confirmation = OrderConfirmationTemplates.confirmation(locale, orderId.toString());
                jdbc.update("""
                        INSERT INTO notification_delivery_outbox(id,event_key,user_id,order_id,subject,body)
                        VALUES (?,?,?,?,?,?) ON CONFLICT (event_key) DO NOTHING
                        """, UUID.randomUUID(), "order-email:" + orderId, userId, orderId,
                        confirmation.subject(), confirmation.body());
            }
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception failure) {
            throw new IllegalStateException("Order event could not be processed", failure);
        }
    }

    private static String requiredHeader(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) throw new IllegalArgumentException("Order event header is missing: " + name);
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
