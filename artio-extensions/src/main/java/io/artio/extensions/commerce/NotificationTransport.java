package io.artio.extensions.commerce;

import java.util.Map;

/** Provider-neutral outbound message transport. Core owns durable delivery and retries. */
public interface NotificationTransport {
    DeliveryResult deliver(OutboundMessage message);

    record OutboundMessage(String idempotencyKey, String channel, String destination,
                           String subject, String body, Map<String, String> metadata) {
        public OutboundMessage { metadata = metadata == null ? Map.of() : Map.copyOf(metadata); }
    }
    record DeliveryResult(boolean accepted, String providerReference, String retryAfter,
                          String failureCode) { }
}
