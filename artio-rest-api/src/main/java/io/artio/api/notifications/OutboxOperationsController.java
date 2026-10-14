package io.artio.api.notifications;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Safe operational counts and redrive controls for durable delivery queues. */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/admin/operations/outbox")
public class OutboxOperationsController {
    private final JdbcTemplate jdbc;
    private final int webhookReceiptRetentionDays;

    public OutboxOperationsController(JdbcTemplate jdbc,
            @Value("${artio.payments.webhook-receipt-retention-days:90}") int webhookReceiptRetentionDays) {
        this.jdbc = jdbc;
        this.webhookReceiptRetentionDays = webhookReceiptRetentionDays;
    }

    @GetMapping
    public OutboxSummary summary() {
        return jdbc.queryForObject("""
                SELECT
                    (SELECT count(*) FROM event_outbox WHERE status='PENDING') AS event_pending,
                    (SELECT count(*) FROM event_outbox WHERE status='PROCESSING' AND locked_at < now()-interval '5 minutes') AS event_stale_claims,
                    (SELECT count(*) FROM event_outbox WHERE status='DEAD') AS event_dead,
                    (SELECT count(*) FROM notification_delivery_outbox WHERE status='PENDING') AS email_pending,
                    (SELECT count(*) FROM notification_delivery_outbox WHERE status='PROCESSING' AND locked_at < now()-interval '5 minutes') AS email_stale_claims,
                    (SELECT count(*) FROM notification_delivery_outbox WHERE status='DEAD') AS email_dead,
                    (SELECT count(*) FROM payment_webhook_receipts WHERE processed_at IS NULL) AS webhook_unprocessed,
                    (SELECT count(*) FROM payment_webhook_receipts WHERE processed_at IS NOT NULL AND processed_at < now() - (? * interval '1 day')) AS webhook_older_than_retention
                """, (rs, row) -> new OutboxSummary(rs.getLong("event_pending"), rs.getLong("event_stale_claims"),
                rs.getLong("event_dead"), rs.getLong("email_pending"), rs.getLong("email_stale_claims"), rs.getLong("email_dead"),
                rs.getLong("webhook_unprocessed"), rs.getLong("webhook_older_than_retention")), webhookReceiptRetentionDays);
    }

    @GetMapping("/events/dead")
    public List<DeadEvent> deadEvents(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (page < 0 || size < 1 || size > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination");
        return jdbc.query("SELECT id,event_type,aggregate_type,aggregate_id,attempts,dead_at,last_error FROM event_outbox WHERE status='DEAD' ORDER BY dead_at DESC LIMIT ? OFFSET ?",
                (rs, row) -> new DeadEvent(rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"), rs.getInt("attempts"), rs.getTimestamp("dead_at").toInstant(), rs.getString("last_error")),
                size, (long) page * size);
    }

    @PostMapping("/events/{eventId}/replay")
    public ReplayResult replay(@PathVariable UUID eventId) {
        int updated = jdbc.update("UPDATE event_outbox SET status='PENDING',attempts=0,available_at=now(),locked_at=NULL,dead_at=NULL,last_error=NULL WHERE id=? AND status='DEAD'",
                eventId);
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Event is not in the dead-letter state");
        return new ReplayResult(eventId, "PENDING");
    }

    public record OutboxSummary(long eventPending, long eventStaleClaims, long eventDead, long emailPending,
            long emailStaleClaims, long emailDead, long webhookUnprocessed, long webhookOlderThanRetention) { }
    public record DeadEvent(UUID id, String eventType, String aggregateType, String aggregateId,
            int attempts, Instant deadAt, String errorClass) { }
    public record ReplayResult(UUID eventId, String status) { }
}
