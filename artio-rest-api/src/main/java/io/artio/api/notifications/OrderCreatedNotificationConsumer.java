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
    private static final java.util.Set<String> SUPPORTED_ORDER_EVENTS = java.util.Set.of(
            "ORDER_CREATED", "ORDER_CANCELLED", "ORDER_PAID", "ORDER_SHIPPED", "ORDER_DELIVERED",
            "ORDER_RETURN_REQUESTED", "ORDER_RETURN_COMPLETED", "PAYMENT_REFUNDED", "SHIPMENT_TRACKING_UPDATED");

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
            int schemaVersion = event.path("schemaVersion").asInt(-1);
            String eventType = event.path("type").asText();
            java.time.Instant.parse(event.path("occurredAt").asText());

            if (!event.isObject() || !event.path("schemaVersion").isIntegralNumber() || schemaVersion != 1
                    || !event.path("type").isTextual() || !SUPPORTED_ORDER_EVENTS.contains(eventType)
                    || !event.path("occurredAt").isTextual()
                    || !eventId.toString().equals(requiredHeader(record, "event-id"))
                    || !eventType.equals(requiredHeader(record, "event-type"))
                    || !"1".equals(requiredHeader(record, "schema-version"))
                    || !orderId.toString().equals(record.key())) {
                throw new IllegalArgumentException("Order event envelope is invalid or unsupported");
            }

            UUID userId = null;
            String locale = null;
            if ("ORDER_CREATED".equals(eventType)) {
                userId = UUID.fromString(event.path("userId").asText());
                locale = event.path("locale").asText();
                if (!event.path("userId").isTextual() || !event.path("locale").isTextual()
                        || !locale.matches("[a-z]{2}(-[A-Z]{2})?")) {
                    throw new IllegalArgumentException("Order-created notification data is invalid");
                }
            }

            int received = jdbc.update("""
                    INSERT INTO event_inbox(consumer_name,event_id,event_type,schema_version,aggregate_id)
                    VALUES (?,?,?,?,?) ON CONFLICT (consumer_name,event_id) DO NOTHING
                    """, CONSUMER_NAME, eventId, eventType, schemaVersion, orderId.toString());
            if (received == 0) return;

            String publicEventType = publicEventType(eventType);
            if (publicEventType != null) {
                // Freeze the subscription and runtime/configuration target in the inbox transaction.
                // Plugin code never receives Kafka credentials and retries never re-resolve routing.
                String payload = mapper.writeValueAsString(java.util.Map.of(
                        "schemaVersion", schemaVersion,
                        "eventId", eventId.toString(),
                        "eventType", publicEventType,
                        "occurredAt", event.path("occurredAt").asText(),
                        "aggregateId", orderId.toString(),
                        "data", java.util.Map.of("orderId", orderId.toString())));
                jdbc.update("""
                        INSERT INTO extension_event_deliveries(delivery_id,inbox_consumer_name,inbox_event_id,
                            subscription_id,instance_id,plugin_id,runtime_identity,release_id,configuration_generation,
                            runtime_credential_generation,public_configuration_snapshot,encrypted_secrets_snapshot,
                            event_type,schema_version,payload_snapshot)
                        SELECT md5(? || ':' || s.subscription_id::text)::uuid, ?, ?, s.subscription_id, i.instance_id,
                            i.plugin_id, i.runtime_identity, i.release_id, i.configuration_generation,p.credential_generation,
                            i.public_configuration, i.encrypted_secrets, s.event_type, s.schema_version, ?::jsonb
                        FROM extension_event_subscriptions s
                        JOIN extension_plugin_instances i ON i.instance_id=s.instance_id
                        JOIN extension_plugins p ON p.plugin_id=i.plugin_id
                        WHERE s.event_type=? AND s.schema_version=? AND s.subscription_state='ACTIVE'
                          AND i.compatibility_mode='CAPABILITY_V2' AND i.lifecycle_state='ENABLED'
                        ON CONFLICT (inbox_consumer_name,inbox_event_id,subscription_id) DO NOTHING
                        """, eventId.toString(), CONSUMER_NAME, eventId, payload, publicEventType, schemaVersion);
            }

            // This consumer group only owns order-confirmation email. Other known business events are
            // durably deduplicated here and remain available on the topic to their own consumer groups.
            if (!"ORDER_CREATED".equals(eventType)) return;

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

    private static String publicEventType(String eventType) {
        return switch (eventType) {
            case "ORDER_CREATED" -> "order.created";
            case "ORDER_CANCELLED" -> "order.cancelled";
            case "ORDER_PAID" -> "order.paid";
            case "ORDER_SHIPPED" -> "order.shipped";
            case "ORDER_DELIVERED" -> "order.delivered";
            case "PAYMENT_REFUNDED" -> "order.refunded";
            default -> null;
        };
    }

    private static String requiredHeader(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) throw new IllegalArgumentException("Order event header is missing: " + name);
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
