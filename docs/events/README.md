# Artio event contracts

Kafka is required platform infrastructure in the default Docker Compose installation. PostgreSQL transactions write events to `event_outbox`; the publisher sends only committed rows to Kafka. Kafka delivery is at least once: a process can fail after the broker accepts an event but before PostgreSQL records `PUBLISHED`, so every consumer must deduplicate by the stable `eventId` before applying effects.

New consumer groups start at the earliest retained offset, so events published before the first assignment are not silently skipped. Existing committed offsets remain authoritative; inbox deduplication protects replay of retained events.

## Order events v1

- **Topic:** `artio.order.events`
- **Partition key:** `aggregate_id` (the order UUID), preserving order for events of one order.
- **Retention:** seven days in the single-broker development/default Compose topology. Production deployments must size retention against the longest supported consumer outage and backup policy.
- **Headers:** `event-id`, `event-type`, and `schema-version` (UTF-8 strings).
- **Payloads:** [`ORDER_CREATED` v1](order-created-v1.schema.json) and the shared [order business event v1](order-business-event-v1.schema.json).

`ORDER_CREATED` is inserted in the same PostgreSQL transaction as the order, line items, and inventory reservations. The notification worker consumes it and inserts the email job into `notification_delivery_outbox` in the same database transaction as its inbox receipt. A failed effect rolls back the inbox record so Kafka can retry; a duplicate event ID is acknowledged without creating duplicate email work. The schema version is `1`. Additive or incompatible contract changes require an explicit new schema version and compatible consumer rollout; never reinterpret an already published version.

The same transactional outbox now emits order lifecycle events for payment confirmation, cancellation, shipment, delivery, return request/completion, refund and tracking updates when their timeline transition is written. Those event payloads contain only the stable event/order IDs, type, schema version and timestamp; timeline text, customer data and provider payloads are excluded. The notification consumer validates and records inbox deduplication for known order business events, but only `ORDER_CREATED` creates an email. Dedicated business consumers can use the same topic and contract without coupling their work to notification delivery.

The publisher retries transient failures with capped exponential backoff. After twelve attempts, it moves the row to `DEAD`; the Backoffice Operations screen exposes safe metadata and an administrator can explicitly requeue it. Requeue preserves the event ID, so consumer inbox deduplication is mandatory. Payloads and customer data are not exposed by the diagnostics endpoint.

The default Compose deployment runs HTTP traffic in `rest-api`, the transactional outbox publisher, Kafka consumers and commerce maintenance schedules in `event-worker`, and durable email delivery in a separate `notification-worker`. The notification worker polls PostgreSQL and calls the configured provider-neutral notification transport; it does not run Kafka consumers or commerce maintenance. These services can scale independently, and the outbox claim uses row locks with `SKIP LOCKED`. The event consumer uses an `event_inbox` primary key `(consumer_name,event_id)`, disables Kafka auto-commit, and commits the record offset after the listener transaction succeeds. Five bounded retries are followed by publication to `artio.order.events.dlq`; this topic is initialized with the main topic. Consumer lag metrics, dedicated replay tooling for Kafka DLQ records, and multi-replica verification remain open in the roadmap. PostgreSQL and Kafka Testcontainers coverage is defined but has not been executed successfully when Docker Engine is unavailable; do not interpret Compose configuration or compilation as runtime verification.
