# Artio event contracts

Kafka is required platform infrastructure in the default Docker Compose installation. PostgreSQL transactions write events to `event_outbox`; the publisher sends only committed rows to Kafka. Kafka delivery is at least once: a process can fail after the broker accepts an event but before PostgreSQL records `PUBLISHED`, so every consumer must deduplicate by the stable `eventId` before applying effects.

## Order events v1

- **Topic:** `artio.order.events`
- **Partition key:** `aggregate_id` (the order UUID), preserving order for events of one order.
- **Retention:** seven days in the single-broker development/default Compose topology. Production deployments must size retention against the longest supported consumer outage and backup policy.
- **Headers:** `event-id`, `event-type`, and `schema-version` (UTF-8 strings).
- **Payload:** [OrderCreated v1 JSON Schema](order-created-v1.schema.json).

`ORDER_CREATED` is inserted in the same PostgreSQL transaction as the order, line items, inventory reservations, and notification delivery request. The schema version is `1`. Additive or incompatible contract changes require an explicit new schema version and compatible consumer rollout; never reinterpret an already published version.

The publisher retries transient failures with capped exponential backoff. After twelve attempts, it moves the row to `DEAD`; the Backoffice Operations screen exposes safe metadata and an administrator can explicitly requeue it. Requeue preserves the event ID, so consumer inbox deduplication is mandatory. Payloads and customer data are not exposed by the diagnostics endpoint.

Current limitation: event publication still runs in the REST API process and no independent Kafka consumer service is implemented yet. Consumer inbox/deduplication, consumer-side dead-letter handling, consumer lag monitoring, and multi-replica verification remain open in the roadmap. PostgreSQL integration coverage for producer exhaustion/redrive exists but was skipped in the latest run because Docker Engine was unavailable; do not interpret Compose configuration or a mocked publisher test as runtime Kafka verification.
