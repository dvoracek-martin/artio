package io.artio.api.notifications;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Payload-free administrative view and replay control for Kafka consumer dead letters. */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/admin/operations/kafka/dead-letters")
public class KafkaDeadLetterOperationsController {
    private final JdbcTemplate jdbc;

    public KafkaDeadLetterOperationsController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public DeadLetterSummary summary() {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE status='DEAD') AS dead,
                       count(*) FILTER (WHERE status IN ('REPLAY_REQUESTED','REPLAYING')) AS replay_pending,
                       count(*) FILTER (WHERE status='REPLAYED') AS replayed
                FROM kafka_consumer_dead_letters
                """, (rs, row) -> new DeadLetterSummary(rs.getLong("dead"), rs.getLong("replay_pending"), rs.getLong("replayed")));
    }

    @GetMapping("/items")
    public List<DeadLetter> items(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        if (page < 0 || size < 1 || size > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination");
        return jdbc.query("""
                SELECT id,event_id,event_type,source_topic,source_partition,source_offset,error_class,status,
                       replay_attempts,last_replay_error,dead_at,replayed_at
                FROM kafka_consumer_dead_letters ORDER BY dead_at DESC LIMIT ? OFFSET ?
                """, (rs, row) -> new DeadLetter(rs.getObject("id", UUID.class), rs.getString("event_id"),
                rs.getString("event_type"), rs.getString("source_topic"), rs.getInt("source_partition"),
                rs.getLong("source_offset"), rs.getString("error_class"), rs.getString("status"),
                rs.getInt("replay_attempts"), rs.getString("last_replay_error"),
                rs.getTimestamp("dead_at").toInstant(), rs.getTimestamp("replayed_at") == null ? null : rs.getTimestamp("replayed_at").toInstant()),
                size, (long) page * size);
    }

    @PostMapping("/{id}/replay")
    public ReplayAccepted replay(@PathVariable UUID id) {
        int updated = jdbc.update("UPDATE kafka_consumer_dead_letters SET status='REPLAY_REQUESTED',replay_attempts=0,available_at=now(),locked_at=NULL,last_replay_error=NULL WHERE id=? AND status='DEAD'", id);
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Dead letter is not available for replay");
        return new ReplayAccepted(id, "REPLAY_REQUESTED");
    }

    public record DeadLetterSummary(long dead, long replayPending, long replayed) { }
    public record DeadLetter(UUID id, String eventId, String eventType, String sourceTopic, int partition,
            long offset, String errorClass, String status, int replayAttempts, String lastReplayError,
            Instant deadAt, Instant replayedAt) { }
    public record ReplayAccepted(UUID id, String status) { }
}
