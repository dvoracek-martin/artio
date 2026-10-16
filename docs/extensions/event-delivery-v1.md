# Plugin event delivery v1

Artio owns Kafka access and persists a minimized delivery for each active v2 subscription in the same PostgreSQL transaction as the consumer inbox receipt. Each delivery freezes its target instance, release, runtime credential generation, public configuration, encrypted secrets and payload. Plugins receive neither Kafka nor PostgreSQL credentials. Delivery uses the authenticated events.subscriber.v1/deliver runtime operation and succeeds only when the plugin returns both accepted: true and the exact stable deliveryId.

The worker claims deliveries with a lease and fencing token, invokes each subscriber independently, retries transient failures with bounded exponential delay, and moves the twelfth failed attempt to DEAD_LETTER. A dead delivery can be replayed by an administrator through POST /api/v1/admin/extensions/events/{deliveryId}/replay; the original frozen target, payload and delivery identity are retained, and the replay is audited. A disabled subscription does not receive newly consumed events; already queued deliveries remain owned by that subscription and can be drained or replayed.

## Controlled backfill

A later subscriber never receives a historical event as part of normal fan-out. An administrator must first create an explicit preview:

```http
POST /api/v1/admin/extensions/events/backfill/preview
Content-Type: application/json

{
  "subscriptionId": "00000000-0000-0000-0000-000000000000",
  "from": "2026-10-15T10:00:00Z",
  "to": "2026-10-15T11:00:00Z",
  "maxEvents": 100,
  "ratePerMinute": 10
}
```

The preview is limited to published, supported v1 order events from the previous seven days, one active subscription on an enabled instance, at most 500 events, and a delivery rate of 1–60 events per minute. It stores the exact event ID set and expires after 15 minutes. Narrow the time range if it exceeds the requested maximum; the system never truncates a preview silently.

Execute a preview once with POST /api/v1/admin/extensions/events/backfill/{runId}/execute. The queue creates a distinct inbox and delivery generation, snapshots the currently authorized subscription target, schedules deliveries at the requested rate, and records an audit event. Repeating execution is idempotent. The existing worker handles delivery and updates the run to COMPLETE or PARTIAL_FAILURE; a failed item can use the normal audited dead-letter replay. GET /api/v1/admin/extensions/events/backfill/{runId} exposes only the run scope, range, rate, count and status.

Preview and execute are separate so an operator can inspect the exact count and source range before work is queued. Execution revalidates subscription ownership, enabled state, retained source rows and event contract. A stale or expired preview must be replaced; it cannot widen its scope or recover data past the retained source window.
