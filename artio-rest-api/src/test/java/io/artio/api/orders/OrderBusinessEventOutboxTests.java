package io.artio.api.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.notifications.OrderCreatedNotificationConsumer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class OrderBusinessEventOutboxTests {
    @Test
    void recordsOrderStateTransitionInTransactionalKafkaOutboxWithoutTimelineText() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID orderId = UUID.randomUUID();

        OrderTimelineEvents.record(jdbc, orderId, null, "ORDER_PAID", "Payment confirmed", true);

        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(eq("INSERT INTO event_outbox(id,event_type,aggregate_type,aggregate_id,payload) VALUES (?,?, 'ORDER', ?, ?::jsonb)"),
                any(UUID.class), eq("ORDER_PAID"), eq(orderId.toString()), payload.capture());
        var event = new ObjectMapper().findAndRegisterModules().readTree(payload.getValue());
        assertThat(event.path("type").asText()).isEqualTo("ORDER_PAID");
        assertThat(event.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(event.path("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(event.has("message")).isFalse();
    }

    @Test
    void notificationConsumerDeduplicatesKnownBusinessEventsWithoutSendingConfirmationEmail() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String payload = """
                {"schemaVersion":1,"eventId":"%s","type":"ORDER_PAID","occurredAt":"2026-10-14T10:00:00Z","orderId":"%s"}
                """.formatted(eventId, orderId);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("artio.order.events", 0, 1L, orderId.toString(), payload);
        record.headers().add("event-id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("event-type", "ORDER_PAID".getBytes(StandardCharsets.UTF_8));
        record.headers().add("schema-version", "1".getBytes(StandardCharsets.UTF_8));

        new OrderCreatedNotificationConsumer(jdbc, new ObjectMapper()).consume(record);

        verify(jdbc).update(any(String.class), any(Object[].class));
        verify(jdbc, never()).queryForObject(any(String.class), any(Class.class), any());
    }
}
