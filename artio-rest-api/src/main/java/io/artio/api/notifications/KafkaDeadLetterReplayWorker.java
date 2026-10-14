package io.artio.api.notifications;

import io.artio.api.security.EncryptionService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Redrives operator-approved consumer dead letters through the normal topic; the inbox keeps processing idempotent. */
@Component
@ConditionalOnExpression("'${artio.workers.enabled:false}' == 'true' and '${artio.events.kafka.enabled:false}' == 'true'")
public class KafkaDeadLetterReplayWorker {
    private static final int BATCH_SIZE = 20;
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final EncryptionService encryption;

    public KafkaDeadLetterReplayWorker(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
            EncryptionService encryption) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.encryption = encryption;
    }

    @Scheduled(fixedDelayString = "${artio.events.kafka.dlq-replay-interval-ms:2000}")
    public void publishRequested() {
        List<ReplayRow> rows = jdbc.query("""
                UPDATE kafka_consumer_dead_letters SET status='REPLAYING',locked_at=now(),replay_attempts=replay_attempts+1
                WHERE id IN (
                    SELECT id FROM kafka_consumer_dead_letters
                    WHERE (status='REPLAY_REQUESTED' AND available_at<=now())
                       OR (status='REPLAYING' AND locked_at<now()-interval '5 minutes')
                    ORDER BY available_at,dead_at LIMIT ? FOR UPDATE SKIP LOCKED
                )
                RETURNING id,event_id,event_type,source_topic,record_key,encrypted_payload,payload_is_null,schema_version,replay_attempts
                """, (rs, row) -> new ReplayRow(rs.getObject("id", UUID.class), rs.getString("event_id"), rs.getString("event_type"),
                rs.getString("source_topic"), rs.getString("record_key"), rs.getString("encrypted_payload"), rs.getBoolean("payload_is_null"),
                rs.getString("schema_version"), rs.getInt("replay_attempts")), BATCH_SIZE);
        for (ReplayRow row : rows) publish(row);
    }

    private void publish(ReplayRow row) {
        try {
            String payload = row.payloadIsNull() ? null : encryption.decrypt(row.encryptedPayload());
            var record = new ProducerRecord<String, String>(row.sourceTopic(), row.recordKey(), payload);
            addHeader(record, "event-id", row.eventId());
            addHeader(record, "event-type", row.eventType());
            addHeader(record, "schema-version", row.schemaVersion());
            kafka.send(record).get(10, TimeUnit.SECONDS);
            jdbc.update("UPDATE kafka_consumer_dead_letters SET status='REPLAYED',replayed_at=now(),locked_at=NULL,last_replay_error=NULL WHERE id=? AND status='REPLAYING'",
                    row.id());
        } catch (Exception failure) {
            long delay = Math.min(3600, 1L << Math.min(row.replayAttempts(), 12));
            String errorClass = failure.getCause() == null ? failure.getClass().getSimpleName() : failure.getCause().getClass().getSimpleName();
            String nextState = row.replayAttempts() >= 12 ? "DEAD" : "REPLAY_REQUESTED";
            jdbc.update("UPDATE kafka_consumer_dead_letters SET status=?,available_at=now()+(? * interval '1 second'),locked_at=NULL,last_replay_error=? WHERE id=? AND status='REPLAYING'",
                    nextState, delay, errorClass, row.id());
        }
    }

    private static void addHeader(ProducerRecord<String, String> record, String name, String value) {
        if (value != null) record.headers().add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8)));
    }

    private record ReplayRow(UUID id, String eventId, String eventType, String sourceTopic, String recordKey, String encryptedPayload,
            boolean payloadIsNull, String schemaVersion, int replayAttempts) { }
}
