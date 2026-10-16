package io.artio.api.extensions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Previewed, subscription-scoped replay from retained Core outbox events. */
@Service
public class PluginEventBackfillOperations {
    private static final Duration SOURCE_RETENTION = Duration.ofDays(7);
    private static final Duration PREVIEW_LIFETIME = Duration.ofMinutes(15);
    private static final int MAX_EVENTS = 500;
    private static final int MAX_EVENTS_PER_MINUTE = 60;
    private static final TypeReference<List<UUID>> UUID_LIST = new TypeReference<>() { };
    private static final Map<String, String> PUBLIC_EVENT_TYPES = Map.of(
            "ORDER_CREATED", "order.created", "ORDER_CANCELLED", "order.cancelled", "ORDER_PAID", "order.paid",
            "ORDER_SHIPPED", "order.shipped", "ORDER_DELIVERED", "order.delivered",
            "PAYMENT_REFUNDED", "order.refunded");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PluginEventBackfillOperations(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public Preview preview(UUID subscriptionId, Instant from, Instant to, int maxEvents, int ratePerMinute, UUID actorId) {
        validateRange(from, to, maxEvents, ratePerMinute);
        Subscription target = findActiveSubscription(subscriptionId, false);
        String sourceType = sourceEventType(target.eventType());
        List<UUID> eventIds = jdbc.query("SELECT id FROM event_outbox WHERE status='PUBLISHED' AND event_type=? "
                        + "AND created_at>=? AND created_at<? AND payload->>'schemaVersion'='1' "
                        + "AND payload->>'eventId'=id::text AND payload->>'type'=event_type "
                        + "AND payload->>'orderId'=aggregate_id AND payload->>'occurredAt' IS NOT NULL "
                        + "ORDER BY created_at,id LIMIT ?",
                (rs, row) -> rs.getObject(1, UUID.class), sourceType, Timestamp.from(from), Timestamp.from(to), maxEvents + 1);
        if (eventIds.size() > maxEvents) {
            throw new BackfillRejectedException("The range exceeds maxEvents; narrow the preview range");
        }
        UUID runId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(PREVIEW_LIFETIME);
        jdbc.update("INSERT INTO extension_event_backfill_runs(run_id,plugin_id,subscription_id,from_at,to_at,max_events,"
                        + "rate_per_minute,event_ids,requested_by,expires_at) VALUES (?,?,?,?,?,?,?,?::jsonb,?,?)",
                runId, target.pluginId(), subscriptionId, Timestamp.from(from), Timestamp.from(to), maxEvents, ratePerMinute,
                write(eventIds), actorId, Timestamp.from(expiresAt));
        audit(target.pluginId(), actorId, "PREVIEW_EVENT_BACKFILL", Map.of("runId", runId.toString(),
                "subscriptionId", subscriptionId.toString(), "eventCount", eventIds.size(),
                "from", from.toString(), "to", to.toString(), "ratePerMinute", ratePerMinute));
        return new Preview(runId, target.pluginId(), subscriptionId, eventIds.size(), from, to,
                maxEvents, ratePerMinute, expiresAt);
    }

    @Transactional
    public Queued execute(UUID runId, UUID actorId) {
        BackfillRun run = jdbc.query("SELECT run_id,plugin_id,subscription_id,from_at,to_at,max_events,rate_per_minute,"
                        + "event_ids::text,status,expires_at FROM extension_event_backfill_runs WHERE run_id=? FOR UPDATE",
                rs -> rs.next() ? new BackfillRun(rs.getObject("run_id", UUID.class), rs.getString("plugin_id"),
                        rs.getObject("subscription_id", UUID.class), rs.getTimestamp("from_at").toInstant(),
                        rs.getTimestamp("to_at").toInstant(), rs.getInt("max_events"), rs.getInt("rate_per_minute"),
                        readIds(rs.getString("event_ids")), rs.getString("status"), rs.getTimestamp("expires_at").toInstant()) : null,
                runId);
        if (run == null) throw new BackfillRejectedException("Backfill preview does not exist");
        if (!"PREVIEWED".equals(run.status())) {
            return new Queued(run.runId(), run.pluginId(), run.eventIds().size(), run.status());
        }
        if (!run.expiresAt().isAfter(Instant.now())) {
            jdbc.update("UPDATE extension_event_backfill_runs SET status='EXPIRED' WHERE run_id=?", runId);
            return new Queued(runId, run.pluginId(), run.eventIds().size(), "EXPIRED");
        }
        Subscription target = findActiveSubscription(run.subscriptionId(), true);
        if (!run.pluginId().equals(target.pluginId())) throw new BackfillRejectedException("Subscription ownership changed");
        String sourceType = sourceEventType(target.eventType());
        String idMarkers = String.join(",", java.util.Collections.nCopies(run.eventIds().size(), "?"));
        List<Object> sourceParameters = new java.util.ArrayList<>(run.eventIds());
        sourceParameters.add(sourceType);
        sourceParameters.add(Timestamp.from(run.from()));
        sourceParameters.add(Timestamp.from(run.to()));
        List<SourceEvent> events = run.eventIds().isEmpty() ? List.of() : jdbc.query(
                "SELECT id,event_type,aggregate_id,payload->>'occurredAt' AS occurred_at "
                        + "FROM event_outbox WHERE status='PUBLISHED' AND id IN (" + idMarkers + ") AND event_type=? "
                        + "AND created_at>=? AND created_at<? AND payload->>'schemaVersion'='1' "
                        + "AND payload->>'eventId'=id::text AND payload->>'type'=event_type "
                        + "AND payload->>'orderId'=aggregate_id AND payload->>'occurredAt' IS NOT NULL ORDER BY created_at,id",
                (rs, row) -> new SourceEvent(rs.getObject("id", UUID.class), rs.getString("event_type"),
                        rs.getString("aggregate_id"), rs.getString("occurred_at")), sourceParameters.toArray());
        if (events.size() != run.eventIds().size()) throw new BackfillRejectedException("Preview source changed; create a new preview");

        // Serialize generation allocation for a subscription so concurrent replay plans cannot collide.
        Integer nextGeneration = jdbc.queryForObject("SELECT COALESCE(MAX(delivery_generation),0)+1 "
                + "FROM extension_event_deliveries WHERE subscription_id=?", Integer.class, run.subscriptionId());
        String inboxConsumer = "plugin-backfill-" + run.runId();
        int inserted = 0;
        long intervalMillis = Math.max(1_000L, 60_000L / run.ratePerMinute());
        for (int index = 0; index < events.size(); index++) {
            SourceEvent event = events.get(index);
            jdbc.update("INSERT INTO event_inbox(consumer_name,event_id,event_type,schema_version,aggregate_id) "
                            + "VALUES (?,?,?,1,?) ON CONFLICT (consumer_name,event_id) DO NOTHING",
                    inboxConsumer, event.eventId(), event.sourceType(), event.aggregateId());
            UUID deliveryId = UUID.nameUUIDFromBytes((event.eventId() + ":" + run.subscriptionId() + ":" + run.runId())
                    .getBytes(StandardCharsets.UTF_8));
            int generation = nextGeneration + index;
            long delayMillis = Math.multiplyExact(intervalMillis, index);
            String payload = write(Map.of("schemaVersion", 1, "eventId", event.eventId().toString(),
                    "eventType", target.eventType(), "occurredAt", event.occurredAt(), "aggregateId", event.aggregateId(),
                    "data", Map.of("orderId", event.aggregateId())));
            inserted += jdbc.update("INSERT INTO extension_event_deliveries(delivery_id,delivery_generation,backfill_run_id,"
                            + "inbox_consumer_name,inbox_event_id,subscription_id,instance_id,plugin_id,runtime_identity,release_id,"
                            + "configuration_generation,runtime_credential_generation,public_configuration_snapshot,encrypted_secrets_snapshot,"
                            + "event_type,schema_version,payload_snapshot,available_at) "
                            + "SELECT ?,?,?,?, ?,s.subscription_id,i.instance_id,i.plugin_id,i.runtime_identity,i.release_id,"
                            + "i.configuration_generation,p.credential_generation,i.public_configuration,i.encrypted_secrets,"
                            + "s.event_type,s.schema_version,?::jsonb,now()+(? * interval '1 millisecond') "
                            + "FROM extension_event_subscriptions s JOIN extension_plugin_instances i ON i.instance_id=s.instance_id "
                            + "JOIN extension_plugins p ON p.plugin_id=i.plugin_id WHERE s.subscription_id=? "
                            + "AND s.subscription_state='ACTIVE' AND i.lifecycle_state='ENABLED' "
                            + "ON CONFLICT (inbox_consumer_name,inbox_event_id,subscription_id,delivery_generation) DO NOTHING",
                    deliveryId, generation, run.runId(), inboxConsumer, event.eventId(), payload, delayMillis, run.subscriptionId());
        }
        if (inserted != events.size()) throw new BackfillRejectedException("Subscription eligibility changed while queuing replay");
        String runState = inserted == 0 ? "COMPLETE" : "QUEUED";
        jdbc.update("UPDATE extension_event_backfill_runs SET status=?,executed_at=now() WHERE run_id=? AND status='PREVIEWED'",
                runState, runId);
        audit(run.pluginId(), actorId, "EXECUTE_EVENT_BACKFILL", Map.of("runId", runId.toString(),
                "subscriptionId", run.subscriptionId().toString(), "eventCount", inserted,
                "ratePerMinute", run.ratePerMinute()));
        return new Queued(runId, run.pluginId(), inserted, runState);
    }

    private Subscription findActiveSubscription(UUID subscriptionId, boolean lock) {
        String sql = "SELECT s.event_type,i.plugin_id FROM extension_event_subscriptions s "
                + "JOIN extension_plugin_instances i ON i.instance_id=s.instance_id WHERE s.subscription_id=? "
                + "AND s.subscription_state='ACTIVE' AND i.lifecycle_state='ENABLED' "
                + (lock ? "FOR UPDATE OF s,i" : "");
        Subscription target = jdbc.query(sql, rs -> rs.next()
                ? new Subscription(rs.getString("event_type"), rs.getString("plugin_id")) : null, subscriptionId);
        if (target == null) throw new BackfillRejectedException("Only an active subscription on an enabled instance can be replayed");
        sourceEventType(target.eventType());
        return target;
    }

    private static void validateRange(Instant from, Instant to, int maxEvents, int ratePerMinute) {
        Instant now = Instant.now();
        if (from == null || to == null || !from.isBefore(to) || from.isBefore(now.minus(SOURCE_RETENTION)) || to.isAfter(now)) {
            throw new BackfillRejectedException("Source range must be within the last seven days and end in the past");
        }
        if (Duration.between(from, to).compareTo(SOURCE_RETENTION) > 0 || maxEvents < 1 || maxEvents > MAX_EVENTS
                || ratePerMinute < 1 || ratePerMinute > MAX_EVENTS_PER_MINUTE) {
            throw new BackfillRejectedException("Use a range of at most seven days, maxEvents 1..500 and ratePerMinute 1..60");
        }
    }

    private static String sourceEventType(String publicType) {
        return PUBLIC_EVENT_TYPES.entrySet().stream().filter(entry -> entry.getValue().equals(publicType))
                .map(Map.Entry::getKey).findFirst()
                .orElseThrow(() -> new BackfillRejectedException("This event contract cannot be backfilled"));
    }

    private void audit(String pluginId, UUID actorId, String action, Map<String, Object> details) {
        jdbc.update("INSERT INTO extension_audit_events(id,plugin_id,actor_user_id,action,details) VALUES (?,?,?, ?,?::jsonb)",
                UUID.randomUUID(), pluginId, actorId, action, write(details));
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Could not serialize event backfill metadata", exception); }
    }

    private List<UUID> readIds(String value) {
        try { return mapper.readValue(value, UUID_LIST); }
        catch (Exception exception) { throw new IllegalStateException("Stored event backfill preview is invalid", exception); }
    }

    public record Preview(UUID runId, String pluginId, UUID subscriptionId, int eventCount, Instant from, Instant to,
            int maxEvents, int ratePerMinute, Instant expiresAt) { }
    public record Queued(UUID runId, String pluginId, int eventCount, String state) { }
    private record Subscription(String eventType, String pluginId) { }
    private record SourceEvent(UUID eventId, String sourceType, String aggregateId, String occurredAt) { }
    private record BackfillRun(UUID runId, String pluginId, UUID subscriptionId, Instant from, Instant to, int maxEvents,
            int ratePerMinute, List<UUID> eventIds, String status, Instant expiresAt) { }

    public static class BackfillRejectedException extends RuntimeException {
        public BackfillRejectedException(String message) { super(message); }
    }
}
