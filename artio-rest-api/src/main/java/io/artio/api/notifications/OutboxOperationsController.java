package io.artio.api.notifications;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Safe operational counts for durable event and notification queues. */
@RestController
@RequestMapping("/api/admin/operations/outbox")
public class OutboxOperationsController {
    private final JdbcTemplate jdbc;

    public OutboxOperationsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public OutboxSummary summary() {
        return jdbc.queryForObject("""
                SELECT
                    (SELECT count(*) FROM event_outbox WHERE status='PENDING') AS event_pending,
                    (SELECT count(*) FROM event_outbox WHERE status='PROCESSING' AND locked_at < now()-interval '5 minutes') AS event_stale_claims,
                    (SELECT count(*) FROM notification_delivery_outbox WHERE status='PENDING') AS email_pending,
                    (SELECT count(*) FROM notification_delivery_outbox WHERE status='PROCESSING' AND locked_at < now()-interval '5 minutes') AS email_stale_claims,
                    (SELECT count(*) FROM notification_delivery_outbox WHERE status='DEAD') AS email_dead,
                    (SELECT count(*) FROM payment_webhook_receipts WHERE processed_at IS NULL) AS webhook_unprocessed,
                    (SELECT count(*) FROM payment_webhook_receipts WHERE received_at < now()-interval '90 days') AS webhook_older_than_retention
                """, (rs, row) -> new OutboxSummary(rs.getLong("event_pending"), rs.getLong("event_stale_claims"),
                rs.getLong("email_pending"), rs.getLong("email_stale_claims"), rs.getLong("email_dead"),
                rs.getLong("webhook_unprocessed"), rs.getLong("webhook_older_than_retention")));
    }

    public record OutboxSummary(long eventPending, long eventStaleClaims, long emailPending,
            long emailStaleClaims, long emailDead, long webhookUnprocessed, long webhookOlderThanRetention) { }
}
