package io.artio.api.orders;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Small persistence helper so order transitions and their timeline records share one transaction. */
public final class OrderTimelineEvents {
    private OrderTimelineEvents() { }

    public static void record(JdbcTemplate jdbc, UUID orderId, UUID actorId, String type, String message, boolean customerVisible) {
        jdbc.update("INSERT INTO order_timeline_events(id,order_id,actor_id,event_type,message,customer_visible) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), orderId, actorId, type, message, customerVisible);
    }
}
