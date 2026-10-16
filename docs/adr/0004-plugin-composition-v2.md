# ADR 0004: Core-owned plugin composition and instance identity

## Status

Accepted for implementation under Master Plan v5.1. The v1 manifest and its existing single-plugin runtime remain supported through an explicit compatibility adapter.

## Context

The current extension registry treats a package identifier as both plugin identity and the configured runtime. That is sufficient for the existing v1 lifecycle but cannot safely represent several merchant accounts, scoped capability bindings, immutable historical ownership, or durable event subscriptions. A manifest is untrusted package metadata; it must never decide which provider handles a payment or receive database/Kafka credentials.

## Decision

Core owns a closed, versioned capability registry. Each registered operation defines its required permission, whether it has external side effects, and its execution policy. Unknown capability and operation identifiers fail closed. Payment capture, cancellation, refund, webhook verification and reconciliation, and historical shipment operations use a persisted pinned owner. Quotes and payment-method discovery may aggregate read-only results. A side effect must be assigned to one instance; facts may fan out only through the separate durable event-delivery system.

Package identity (`pluginId`), immutable release identity, configured deployment identity (`pluginInstanceId`), and authorization binding identity (`bindingId`) are separate concepts. Release and instance configuration generations are immutable references in persisted decisions. A v1 manifest continues to use its current `pluginId` runtime through the compatibility adapter; v1 extension points are not silently inferred as v2 capability declarations.

Deployment lifecycle, binding eligibility, and the selected historical owner are separate state. Draining prevents new commitments while permitting already-approved settlement and reconciliation. Disable, unhealthy state, or uninstall cannot erase an obligation or its durable delivery record.

## Consequences

- Core validates declarations against the registry and persists a side-effect-free routing decision before an external non-idempotent call.
- Operation attempts are idempotent by key and request fingerprint, claimed with a lease/fencing token, and never hold a database transaction open during the plugin call.
- External outcomes that cannot be established remain pending reconciliation; retry does not select another provider.
- Plugin containers remain Docker-only and receive neither PostgreSQL nor Kafka credentials.
- v1 local/S3 storage and existing commerce integrations remain defaults while migrations add instance-aware bindings and object placement incrementally.

## Acceptance evidence

The ADR is not an acceptance gate by itself. The roadmap requires schema/backward-compatibility, routing and ledger, payment/shipping, Kafka delivery, storage migration, Backoffice and security work, followed by the Master Plan v5.1 PostgreSQL/Kafka/Docker end-to-end acceptance matrix.
