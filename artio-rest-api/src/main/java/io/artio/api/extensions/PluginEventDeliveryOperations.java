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
        var delivery = jdbc.query("SELECT plugin_id,backfill_run_id FROM extension_event_deliveries "
                        + "WHERE delivery_id=? AND state='DEAD_LETTER'",
                rs -> rs.next() ? new DeliveryOwner(rs.getString("plugin_id"), rs.getObject("backfill_run_id", UUID.class)) : null,
                deliveryId);
        if (delivery == null) return ReplayResult.NOT_DEAD_LETTER;
        int updated = jdbc.update("UPDATE extension_event_deliveries SET state='PENDING',attempt_count=0,available_at=now(),"
                        + "lease_token=NULL,lease_until=NULL,last_error_code=NULL,updated_at=now() "
                        + "WHERE delivery_id=? AND state='DEAD_LETTER'", deliveryId);
        if (updated != 1) return ReplayResult.CHANGED;
        jdbc.update("INSERT INTO extension_audit_events(id,plugin_id,actor_user_id,action,details) VALUES (?,?,?,'REPLAY_EVENT_DELIVERY',?::jsonb)",
                UUID.randomUUID(), delivery.pluginId(), actorId, "{\"deliveryId\":\"" + deliveryId + "\"}");
        if (delivery.backfillRunId() != null) {
            jdbc.update("UPDATE extension_event_backfill_runs SET status='QUEUED' WHERE run_id=? "
                    + "AND status='PARTIAL_FAILURE'", delivery.backfillRunId());
        }
        return ReplayResult.ACCEPTED;
    }

    private record DeliveryOwner(String pluginId, UUID backfillRunId) { }
    public enum ReplayResult { ACCEPTED, NOT_DEAD_LETTER, CHANGED }
}
