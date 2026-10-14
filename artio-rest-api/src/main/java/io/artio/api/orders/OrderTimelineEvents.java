package io.artio.api.orders;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Small persistence helper so order transitions and their timeline records share one transaction. */
public final class OrderTimelineEvents {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Set<String> BUSINESS_EVENTS = Set.of(
            "ORDER_CANCELLED", "ORDER_PAID", "ORDER_SHIPPED", "ORDER_DELIVERED",
            "RETURN_REQUESTED", "RETURN_COMPLETED", "PAYMENT_REFUNDED", "TRACKING_UPDATED");

    private OrderTimelineEvents() { }

    public static void record(JdbcTemplate jdbc, UUID orderId, UUID actorId, String type, String message, boolean customerVisible) {
        jdbc.update("INSERT INTO order_timeline_events(id,order_id,actor_id,event_type,message,customer_visible) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), orderId, actorId, type, message, customerVisible);
        String eventType = kafkaEventType(type);
        if (eventType != null) {
            UUID eventId = UUID.randomUUID();
            String payload;
            try {
                payload = JSON.writeValueAsString(Map.of("schemaVersion", 1, "eventId", eventId,
                        "type", eventType, "occurredAt", Instant.now(), "orderId", orderId));
            } catch (JsonProcessingException impossible) {
                throw new IllegalStateException("Could not serialize order business event", impossible);
            }
            jdbc.update("INSERT INTO event_outbox(id,event_type,aggregate_type,aggregate_id,payload) VALUES (?,?, 'ORDER', ?, ?::jsonb)",
                    eventId, eventType, orderId.toString(), payload);
        }
    }

    private static String kafkaEventType(String timelineType) {
        if (!BUSINESS_EVENTS.contains(timelineType)) return null;
        return switch (timelineType) {
            case "RETURN_REQUESTED" -> "ORDER_RETURN_REQUESTED";
            case "RETURN_COMPLETED" -> "ORDER_RETURN_COMPLETED";
            case "TRACKING_UPDATED" -> "SHIPMENT_TRACKING_UPDATED";
            default -> timelineType;
        };
    }
}
