package io.artio.api.notifications;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** At-least-once publisher for order events committed to PostgreSQL with their business transaction. */
@Component
@ConditionalOnProperty(prefix = "artio.events.kafka", name = "enabled", havingValue = "true")
public class OrderEventOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OrderEventOutboxPublisher.class);
    private static final int BATCH_SIZE = 100;
    private static final long CLAIM_TIMEOUT_SECONDS = 300;
    private static final long SEND_TIMEOUT_SECONDS = 5;
    private static final int MAX_ATTEMPTS = 12;
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
        List<OutboxRow> claimed = transactions.execute(status -> {
            jdbc.update("""
                    UPDATE event_outbox SET status='DEAD',dead_at=now(),locked_at=NULL,last_error=COALESCE(last_error,'RETRY_EXHAUSTED')
                    WHERE attempts>=? AND ((status='PENDING' AND available_at<=now())
                       OR (status='PROCESSING' AND locked_at < now() - (? * interval '1 second')))
                    """, MAX_ATTEMPTS, CLAIM_TIMEOUT_SECONDS);
            return jdbc.query("""
                UPDATE event_outbox SET status='PROCESSING', attempts=attempts+1, locked_at=now()
                WHERE id IN (
                    SELECT id FROM event_outbox
                    WHERE (status='PENDING' AND available_at<=now())
                       OR (status='PROCESSING' AND locked_at < now() - (? * interval '1 second'))
                    ORDER BY created_at
                    LIMIT ? FOR UPDATE SKIP LOCKED
                )
                RETURNING id,event_type,aggregate_id,payload,attempts
                """, (rs, row) -> new OutboxRow(rs.getObject("id", UUID.class), rs.getString("event_type"),
                rs.getString("aggregate_id"), rs.getString("payload"), rs.getInt("attempts")), CLAIM_TIMEOUT_SECONDS, BATCH_SIZE);
        });
        if (claimed == null) return;
        for (OutboxRow event : claimed) publish(event);
    }

    private void publish(OutboxRow event) {
        try {
            var record = new ProducerRecord<>("artio.order.events", event.aggregateId(), event.payload());
            record.headers().add("event-id", event.id().toString().getBytes(StandardCharsets.UTF_8));
            record.headers().add("event-type", event.eventType().getBytes(StandardCharsets.UTF_8));
            record.headers().add("schema-version", "1".getBytes(StandardCharsets.UTF_8));
            kafka.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            jdbc.update("UPDATE event_outbox SET status='PUBLISHED',published_at=now(),locked_at=NULL,last_error=NULL WHERE id=? AND status='PROCESSING'",
                    event.id());
        } catch (Exception failure) {
            long backoffSeconds = Math.min(3600, 1L << Math.min(event.attempts(), 12));
            String errorType = failure.getCause() == null ? failure.getClass().getSimpleName()
                    : failure.getCause().getClass().getSimpleName();
            if (event.attempts() >= MAX_ATTEMPTS) {
                jdbc.update("UPDATE event_outbox SET status='DEAD',dead_at=now(),locked_at=NULL,last_error=? WHERE id=? AND status='PROCESSING'",
                        errorType, event.id());
                log.error("Outbox event {} exhausted its retry policy and was moved to the dead-letter state", event.id());
            } else {
                jdbc.update("UPDATE event_outbox SET status='PENDING',locked_at=NULL,available_at=?,last_error=? WHERE id=? AND status='PROCESSING'",
                        Timestamp.from(Instant.now().plusSeconds(backoffSeconds)), errorType, event.id());
                log.warn("Outbox event {} could not be published; retry scheduled", event.id());
            }
        }
    }

    private record OutboxRow(UUID id, String eventType, String aggregateId, String payload, int attempts) { }
}
