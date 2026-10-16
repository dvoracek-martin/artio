package io.artio.api.extensions;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Audited operator actions for durable plugin event deliveries. */
@Service
public class PluginEventDeliveryOperations {
    private final JdbcTemplate jdbc;

    public PluginEventDeliveryOperations(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public ReplayResult replay(UUID deliveryId, UUID actorId) {
        String pluginId = jdbc.query("SELECT plugin_id FROM extension_event_deliveries WHERE delivery_id=? AND state='DEAD_LETTER'",
                rs -> rs.next() ? rs.getString(1) : null, deliveryId);
        if (pluginId == null) return ReplayResult.NOT_DEAD_LETTER;
        int updated = jdbc.update("UPDATE extension_event_deliveries SET state='PENDING',attempt_count=0,available_at=now(),"
                        + "lease_token=NULL,lease_until=NULL,last_error_code=NULL,updated_at=now() "
                        + "WHERE delivery_id=? AND state='DEAD_LETTER'", deliveryId);
        if (updated != 1) return ReplayResult.CHANGED;
        jdbc.update("INSERT INTO extension_audit_events(id,plugin_id,actor_user_id,action,details) VALUES (?,?,?,'REPLAY_EVENT_DELIVERY',?::jsonb)",
                UUID.randomUUID(), pluginId, actorId, "{\"deliveryId\":\"" + deliveryId + "\"}");
        return ReplayResult.ACCEPTED;
    }

    public enum ReplayResult { ACCEPTED, NOT_DEAD_LETTER, CHANGED }
}
