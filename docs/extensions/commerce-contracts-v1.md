# Artio commerce extension contracts, version 1

The Core owns commerce state and transaction boundaries. Docker plugins implement external provider behavior through the versioned extension API. The Java interfaces in `artio-extensions` define the semantic model for plugin SDKs and Core adapters; they are not loaded as in-process provider implementations.

## Payment provider

`io.artio.extensions.commerce.PaymentProvider` defines checkout creation, capability discovery, authorization/capture, cancellation, refunds, webhook verification and reconciliation. Calls carry a stable idempotency key. Providers return their own reference and a neutral state; the Core maps that result to persisted order/payment state inside its own transaction. Webhook event identity is namespaced by provider and deduplicated before applying order changes. Providers may omit operations through their capability declaration. Checkout expiry is optional and expressed as a duration.

The Core retains inventory reservation and compensation guarantees. A provider response never directly writes order state. The Core verifies ownership and current state, then applies a single idempotent transition. Provider retries reuse the original idempotency key.

## Shipping provider

`ShippingProvider` defines method discovery, quotes, shipment creation and tracking, with explicit geographic coverage and capability flags. A carrier may implement quotes without label creation, or tracking without shipment creation. Country availability is discovery metadata and does not prevent a merchant from configuring another compatible market.

## Notification transport

`NotificationTransport` sends an already persisted message and returns an accepted/rejected result. The Core owns the transactional delivery outbox, retry schedule, dead-letter state and redaction. Missing or disabled notification providers leave messages queued and do not prevent checkout or order creation.

## Search provider

`ProductSearchProvider` indexes and removes catalog documents and returns product identifiers for a query. The Core remains authoritative for catalog data and applies normal visibility and availability checks when loading results. If no provider is enabled or healthy, catalog browsing remains available and search degrades to the Core's bounded catalog query.

## Event transport

`EventTransport` publishes events already committed to the Core outbox. The Core's PostgreSQL transaction is the durability boundary. Kafka is required in the standard Artio installation; producer outage leaves events in the PostgreSQL outbox for retry and does not roll back a committed commerce operation. Event contracts carry a stable event ID, event type, schema version, occurrence time, aggregate identity and correlation metadata. Consumers use an inbox/deduplication key and acknowledge only after their database effects commit.

## Runtime mapping

Plugin manifests name the supported `extensionPoints` and permissions. Core invokes plugins over the authenticated Docker runtime API with bounded request/response sizes and timeouts. Each point maps to one request and response schema. Provider credentials are passed only through encrypted plugin configuration and are never part of requests, logs, or returned configuration. Plugins cannot connect to the Core database. See [plugin manifest v1](plugin-manifest-v1.md) for image pinning, geographic availability, and configuration rules.

The interfaces establish the neutral contract shape. They do not claim that Stripe, Swiss Post, SMTP, OpenSearch, or Kafka have already been fully extracted into plugin images; those are separate implementation and acceptance tasks in the Phase 2 ledger.
