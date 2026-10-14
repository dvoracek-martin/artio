package io.artio.extensions.commerce;

import java.time.Instant;
import java.util.Map;

/** Optional transport for already persisted outbox events. */
public interface EventTransport {
    PublishResult publish(CommerceEvent event);

    record CommerceEvent(String eventId, String eventType, Instant occurredAt, String aggregateId,
                         Map<String, Object> payload) {
        public CommerceEvent { payload = payload == null ? Map.of() : Map.copyOf(payload); }
    }
    record PublishResult(boolean accepted, String transportReference, String failureCode) { }
}
