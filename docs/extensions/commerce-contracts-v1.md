# Artio commerce extension contracts, version 1

The Core owns commerce state and transaction boundaries. Docker plugins implement external provider behavior through the versioned extension API. The Java interfaces in `artio-extensions` define the semantic model for plugin SDKs and Core adapters; they are not loaded as in-process provider implementations.

## Payment provider

`io.artio.extensions.commerce.PaymentProvider` defines checkout creation, capability discovery, authorization/capture, cancellation, refunds, webhook verification and reconciliation. Set `ARTIO_PAYMENT_PROVIDER=extension` and `ARTIO_PAYMENTS_EXTENSION_PLUGIN_ID` to use an enabled managed plugin. The `payment.provider.v1` adapter invokes `capabilities`, `createCheckout`, `capture`, `cancel`, `refund`, `verifyWebhook` and `reconcile` with `payment.process`. Checkout and financial mutations carry a stable caller idempotency key; Core derives the runtime UUID from the operation and caller key so retries retain the same invocation identity. Webhook verification receives the unmodified request bytes encoded as Base64 and a Core-allowlisted header map. The allowlist currently includes `Provider-Signature`, `Stripe-Signature`, and PayPal's transmission ID/time/signature, certificate URL and auth algorithm headers; arbitrary headers such as cookies and authorization are never forwarded to the plugin. Providers validate their own signature using these headers and their encrypted configuration. Responses use the corresponding `PaymentProvider` record shapes; reconciliation wraps an optional result in `result`. Providers return their own reference and a neutral state; the Core maps that result to persisted order/payment state inside its own transaction. Webhook event identity is namespaced by provider and deduplicated before applying order changes. Providers may omit operations through their capability declaration. Checkout expiry is optional and expressed as a duration. The existing Stripe endpoints and Stripe reconciliation remain available under the default `ARTIO_PAYMENT_PROVIDER=stripe` mode.

The Core retains inventory reservation and compensation guarantees. A provider response never directly writes order state. The Core verifies ownership and current state, then applies a single idempotent transition. Provider retries reuse the original idempotency key.

## Shipping provider

`ShippingProvider` defines method discovery, quotes, shipment creation and tracking, with explicit geographic coverage and capability flags. Set `ARTIO_SHIPPING_PROVIDER=extension` and `ARTIO_SHIPPING_EXTENSION_PLUGIN_ID` to load an enabled `shipping.provider.v1` plugin. The adapter maps `capabilities`, `methods`, `quote`, `createShipment` and `track`; quote/method operations require `shipping.quote`, and shipment/tracking operations require `shipping.label`. Shipment creation requires a caller-owned idempotency key. A carrier may implement quotes without label creation, or tracking without shipment creation. Country availability is discovery metadata and does not prevent a merchant from configuring another compatible market. The current checkout still uses its configured flat shipping cost, and admin shipment/label workflows have not yet been connected to this adapter.

## Notification transport

`NotificationTransport` sends an already persisted message and returns an accepted/rejected result. The Core owns the transactional delivery outbox, retry schedule, dead-letter state and redaction. SMTP remains the default transport. An installation can select an enabled Docker plugin by setting `ARTIO_EMAIL_PROVIDER=extension` and `ARTIO_NOTIFICATIONS_EXTENSION_PLUGIN_ID`; the plugin declares `notification.channel.v1` and `notifications.send`, then implements the bounded `deliver` operation with the stable delivery ID as its idempotency key. Missing or disabled notification providers leave messages queued and do not prevent checkout or order creation.

## Search provider

`ProductSearchProvider` indexes and removes catalog documents and returns product identifiers for a query. The Core remains authoritative for catalog data and hydrates current product and locale data from PostgreSQL. Configure an enabled Docker provider with `ARTIO_SEARCH_PROVIDER=extension` and `ARTIO_SEARCH_EXTENSION_PLUGIN_ID`; the plugin declares `catalog.search.v1` and `catalog.read`. It implements `index`, `remove`, `search`, and the generation rebuild operations. Catalog browsing remains independent; search requires an enabled search provider.

## Event transport

`EventTransport` publishes events already committed to the Core outbox. The Core's PostgreSQL transaction is the durability boundary. Kafka is required in the standard Artio installation; producer outage leaves events in the PostgreSQL outbox for retry and does not roll back a committed commerce operation. Event contracts carry a stable event ID, event type, schema version, occurrence time, aggregate identity and correlation metadata. Consumers use an inbox/deduplication key and acknowledge only after their database effects commit.

## Runtime mapping

Plugin manifests name the supported `extensionPoints` and permissions. Core invokes plugins over the authenticated Docker runtime API with bounded request/response sizes and timeouts. Each point maps to one request and response schema. Provider credentials are passed only through encrypted plugin configuration and are never part of requests, logs, or returned configuration. Plugins cannot connect to the Core database. See [plugin manifest v1](plugin-manifest-v1.md) for image pinning, geographic availability, and configuration rules.

These contracts do not claim that the reference Stripe, Swiss Post, notification, search, or Kafka plugin images have been built and published. Current SMTP/OpenSearch adapters remain selectable native implementations; publishing reference plugins and completing Docker-backed end-to-end workflows remain in the Phase 2 ledger.
