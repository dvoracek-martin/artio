package io.artio.api.notifications;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** At-least-once publisher for order events committed to PostgreSQL with their business transaction. */
@Component
public class OrderEventOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OrderEventOutboxPublisher.class);
    private static final int BATCH_SIZE = 100;
    private static final long CLAIM_TIMEOUT_SECONDS = 300;
    private static final long SEND_TIMEOUT_SECONDS = 5;
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;

    public OrderEventOutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${artio.events.outbox-poll-interval-ms:1000}")
    public void publishPending() {
        List<OutboxRow> claimed = transactions.execute(status -> jdbc.query("""
                UPDATE event_outbox SET status='PROCESSING', attempts=attempts+1, locked_at=now()
                WHERE id IN (
                    SELECT id FROM event_outbox
                    WHERE (status='PENDING' AND available_at<=now())
                       OR (status='PROCESSING' AND locked_at < now() - (? * interval '1 second'))
                    ORDER BY created_at
                    LIMIT ? FOR UPDATE SKIP LOCKED
                )
                RETURNING id,aggregate_id,payload,attempts
                """, (rs, row) -> new OutboxRow(rs.getObject("id", UUID.class), rs.getString("aggregate_id"),
                rs.getString("payload"), rs.getInt("attempts")), CLAIM_TIMEOUT_SECONDS, BATCH_SIZE));
        if (claimed == null) return;
        for (OutboxRow event : claimed) publish(event);
    }

    private void publish(OutboxRow event) {
        try {
            kafka.send("artio.order.events", event.aggregateId(), event.payload()).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            jdbc.update("UPDATE event_outbox SET status='PUBLISHED',published_at=now(),locked_at=NULL,last_error=NULL WHERE id=? AND status='PROCESSING'",
                    event.id());
        } catch (Exception failure) {
            long backoffSeconds = Math.min(3600, 1L << Math.min(event.attempts(), 12));
            String errorType = failure.getCause() == null ? failure.getClass().getSimpleName()
                    : failure.getCause().getClass().getSimpleName();
            jdbc.update("UPDATE event_outbox SET status='PENDING',locked_at=NULL,available_at=?,last_error=? WHERE id=? AND status='PROCESSING'",
                    Timestamp.from(Instant.now().plusSeconds(backoffSeconds)), errorType, event.id());
            log.warn("Outbox event {} could not be published; retry scheduled", event.id());
        }
    }

    private record OutboxRow(UUID id, String aggregateId, String payload, int attempts) { }
}
