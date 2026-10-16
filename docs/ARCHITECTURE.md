# Artio target architecture

**Status:** target decisions for implementation; this document does not claim every boundary is implemented.
**Authority:** product and architecture choices in the 2026-10-15 integrated master implementation directive v5.1.
**Current evidence and module-by-module gaps:** [repository audit](REPOSITORY_AUDIT.md).

## Product topology

Artio is proprietary software distributed as a self-hosted installation. Community remains useful for real commerce; licensed Enterprise features are enforced in server-side use-case/API boundaries through a local verifier for signed entitlements. Core must continue operating within an unexpired local entitlement when the licensing endpoint is unavailable. The exact edition matrix is a prerequisite for enabling feature gates, not a reason to scatter edition checks through domain code.

The standard product includes **Artio Core** and a **Backoffice**. The **Storefront** is an optional reference client; custom frontends use the same documented Core APIs. Mobile clients are outside the current product scope.

## Core boundaries

Artio is one product deployed as multiple Docker services. Keep strongly transactional commerce operations in the Core API's modular monolith; split background event publication/consumption, notification delivery, and privileged Docker lifecycle control into separate runtime services where their independent security, failure isolation, and scaling needs justify it. Do not create one service per domain. PostgreSQL is transactional truth, and Kafka is required installation infrastructure for asynchronous workflows. Domain events express business facts, not event sourcing. Synchronous application interfaces remain responsible for use cases needing immediate consistency.

The standard `docker compose up -d` topology includes PostgreSQL, a persistent single-node KRaft Kafka broker, topic initialization, the Core API, separate `event-worker` and `notification-worker` processes, an isolated `extension-controller`, and Backoffice. The reference Storefront is an optional deployment extension. Kafka producers use aggregate IDs as partition keys so records for one aggregate retain ordering. Kafka is single-broker in the supported local Compose topology; multi-replica production resilience remains an explicit release gate in the roadmap.

| Context | Owns | Allowed dependencies |
|---|---|---|
| Catalog | Product/variant identity, SKU rules, taxonomy, attributes, media references, localized content and publication lifecycle | Kernel/value primitives; own persistence/search ports |
| Pricing and tax | Price lists, promotions, money, currency, country/region rule selection, rounding and effective dates | Kernel; catalog identity contract, not catalog tables |
| Inventory | Stock, availability, reservation, expiry/release and adjustment history | Kernel; product/sellable identity contract |
| Bundles | Definitions, selection constraints, configuration and bundle-specific lifecycle | Kernel plus explicit pricing/inventory application contracts |
| Cart/checkout | Mutable intent, validation orchestration, idempotency and workflow state | Domain application contracts; never authoritative price/tax/stock tables |
| Orders | Immutable accepted purchase facts, legal state transitions, fulfillment/payment references | Kernel and explicit contracts; owns order snapshots |
| Identity/access | Principals, sessions/credentials, permission policy and administration identity | Kernel; proven identity/security adapters |
| Payment orchestration | Payment intents/attempts, idempotency, state mapping and Core-owned port | Kernel; provider-independent; invokes Extension API |
| Fulfillment | Shipment/consignment/tracking state and Core-owned carrier contract | Kernel; provider-independent; invokes Extension API |
| Notifications/integrations | Delivery jobs/status, retry policy and external webhook subscription contracts | Kernel; asynchronous adapters only where justified |
| Extension management | Manifest validation, entitlements, lifecycle, schema-backed configuration, health, audit and invocation | Core management API plus isolated privileged lifecycle controller; versioned API contract |

| Runtime service | Responsibility | Why it is separate |
|---|---|---|
| Core API | Synchronous commerce, identity, checkout and admin APIs; owns authoritative domain transactions | Preserves checkout, tax and inventory consistency across contexts |
| Event worker | Publish the transactional outbox; consume versioned business events with an inbox and bounded retries | Kafka outage/replay and consumer scaling must not block the request API |
| Notification worker | Deliver persisted messages through provider-neutral Docker plugins | Isolates slow/unavailable providers and separates notification credentials |
| Extension controller | Perform narrowly scoped Docker lifecycle operations | Keeps the Docker socket away from the public commerce API |
| Kafka | Durable asynchronous transport and consumer-group coordination | Required shared infrastructure for recoverable cross-service work |

The Core API, event-worker and notification-worker are separate processes in Compose. The event-worker publishes and consumes Kafka events and runs commerce maintenance schedules. The notification-worker only polls the durable delivery outbox and calls the selected provider-neutral notification transport; it has its own scaling and failure boundary. Extension lifecycle is mediated by the separate controller; the task ledger records remaining service boundaries and bundle-management gaps.

Persistence adapters depend on the domain contracts they implement. A domain must not depend on Spring, database adapters, Kafka clients, UI projects or provider SDKs. REST DTOs remain API concerns, not persistence entities. Cross-context table writes are replaced with owner application calls or an explicit event workflow.

## Docker-only extension boundary

Third-party/provider implementations are separate Docker containers. There is no in-process plugin JAR mode. Each image is identified by immutable image reference/digest and declares a versioned manifest: ID/name/vendor/version, compatibility range/API capabilities, dependencies, permissions, configuration schema, extension points, health/diagnostic metadata and image identity.

Core validates the manifest and authorization before activation. Lifecycle transitions are explicit (`DISCOVERED`, `VALIDATED`, `INSTALLED`, `CONFIGURED`, `ENABLED`, `DISABLED`, `UNHEALTHY`, `UPDATING`, `FAILED`, `UNINSTALLED` as applicable), validated server-side and audited. Exact states and allowed transitions are to be finalized with the first runnable proof and contract tests.

Core-to-plugin calls use a versioned, authenticated, bounded request/response contract with payload limits, deadlines, safe retry/idempotency rules and circuit breaking. Plugin callbacks are disabled unless a separately authenticated callback contract is justified. Default container policy is non-root, read-only filesystem where possible, no privileged mode/host mounts, bounded CPU/memory/PIDs, explicit network egress/ingress and scoped secrets. Health failures/timeouts isolate the plugin and return a stable actionable Core error; they must not terminate Core or corrupt unrelated transactions. Docker is one layer, not a complete sandbox; host/kernel/daemon risk remains with the operator.

The initial registry/trust policy must allow only configured trusted registries or explicitly approved local images. Resolve and record a digest; validate image/manifest compatibility before execution. Updates stage a new image/config version, health-check before switching, preserve the prior reference for rollback where the plugin's data contract allows it, and never silently delete plugin-created business data on uninstall.

## v5.1 plugin composition foundation

Core distinguishes package (`pluginId`), immutable release, configured deployment (`instanceId` with its own runtime identity and credentials), and authorization binding (`bindingId`). A v2 release explicitly declares only operations from the closed Core-owned capability registry. v1 registrations remain explicit `LEGACY_V1` instances and keep the original plugin identity and environment-selector compatibility path. Instance configuration and encrypted secrets are isolated; per-instance Docker lifecycle names and credentials do not collide when one release runs for several merchant accounts.

The Core resolver supports `EXCLUSIVE`, `SELECT_ONE`, `AGGREGATE_READ`, `PINNED_ONE`, and `PRIMARY_REPLICAS` policies. Equal-specificity exclusive scopes that may overlap at equal priority are rejected under a PostgreSQL transaction advisory lock. Side-effect attempts persist a request fingerprint and immutable route/configuration/credential snapshot before the plugin call. Ambiguous outcomes remain attached to their original provider identity; generic extension invocation rejects storage writes because the media placement orchestrator owns that operation. Checkout, shipment creation, historical payment/shipping actions, and media placement use their v2 bindings and persisted owners where configured, while legacy v1 registrations retain their compatibility path.

Kafka event subscriptions are Core-owned: approved event facts are minimized, subscriptions and instance configuration/secrets are frozen into independent durable delivery records in the consumer transaction, and a worker retries with leases and stable delivery identities. Plugin containers receive neither database nor Kafka credentials. The Backoffice exposes package/release and instance-level configuration, health, obligations, drain, binding and subscription controls under scoped authorization.

Development maintains one current PostgreSQL schema snapshot at `artio-rest-api/src/main/resources/db/migration/V1__initial_schema.sql`; historical schema scripts are consolidated rather than replayed on every development install. Production upgrade history starts with released production baselines. Clean schema installation and current-state integration tests pass. Live Docker and PostgreSQL/Kafka acceptance has verified two instances of one release with isolated runtime credentials, frozen multi-subscriber fan-out and retry/rebalance recovery, and primary/replica media persistence, recovery and isolation. Full-system packaging, provider sandbox, production-topology and release acceptance remain tracked in [the v5.1 roadmap](ROADMAP.md).

Plugin configuration schema is untrusted metadata, not authorization. Core validates type/constraints and allowed schema features. Backoffice renders forms from the validated schema; secret values are write-only, encrypted using the deployment-owned secret/key-management strategy, never returned in full, logs, diagnostics or normal exports. Secret rotation requires a documented re-encryption/provider update flow.

### Observability plugin boundary

Artio Core has no Prometheus registry, scrape endpoint, Grafana code, or Jaeger dependency. When enabled, the application exports vendor-neutral OpenTelemetry data over OTLP; export is off by default. Prometheus, Grafana, Jaeger, scrape/alert rules, dashboards and their credentials are independently packaged as the `observability.artio` Docker plugin under `artio-plugins/observability`; the Compose overlay under `artio-docker/observability` is only a local development path. Its manifest declares plugin-owned Prometheus/Grafana volumes, schema-backed encrypted Grafana credentials and dependency-ordered sidecars. The Plugin Manager provides the shared install/configure/enable/disable/update/rollback/uninstall lifecycle. An authenticated, read-only same-origin Backoffice proxy exposes Grafana only while enabled. Live Docker acceptance verified bundle installation, health, authenticated proxying, persistence across restart and cleanup; the remaining P2-03 release gate is publication of trusted immutable plugin digests. Do not add vendor-specific exporters or service implementations to Core to close this gap.

Regional distributions are immutable JSON assets exposed via the public `/api/v1/public/distributions` catalog. `switzerland-v1` declares CH/CHF regional defaults, language/timezone policy and provider plugin IDs; PostFinance Checkout is required for the target Swiss release, while installation and activation remain explicit owner actions. Defaults are discovery metadata and do not overwrite merchant values during ordinary application or upgrades. First-run completion persists each recommended plugin's availability and lifecycle status. SUPERADMIN can review them in System Settings; install/configure/enable remain explicit Plugin Manager actions. A recommendation becomes installable only when its actual trusted, digest-pinned plugin manifest is registered; see [the distribution contract](distributions/switzerland-v1.md).

AI Frontend Builder and AI Module Builder are **DEFERRED – future product decision**. They are outside the initial release scope; no AI-specific extension points, services, APIs, UIs, placeholder modules, or deployment assumptions are to be introduced. Keep general versioned REST and plugin contracts documented for ordinary integrations.

## Globalization and regional policy

Required initial UI/localized-content languages are `de`, `it`, `fr`, `en`. Locale and country/region are separate values. Translation records use stable entity IDs and locale keys, with explicit fallback and validation; editing one locale cannot overwrite another. Search indexes preserve locale and support rebuild. Presentation uses standard locale libraries for money formatting, dates/time zones, numbers and addresses; formatting never changes stored money or tax results.

Tax, invoice, currency, address, payment/shipping availability, units and rounding are country/region configuration, effective-dated where appropriate. Orders preserve the selected policy and tax/price results as historical snapshots. Switzerland is the initial market focus, not a hard-coded domain assumption. Current Swiss legal rates/requirements must be verified from primary authoritative sources before being supplied as defaults.

## Identity, editions and secret boundaries

Authorization is checked on the server for every privileged action and resource. Replace broad hard-coded roles with explicit permission policy as workflows warrant. Enterprise entitlement verification is local and signature-based; a private signing key never ships in Core or customer installations. Entitlement refresh is scheduled/controlled over HTTPS with an explicit grace/failure policy; no synchronous licensing-server dependency is permitted for routine requests.

The owner-approved [edition contract](licensing/entitlements-v1.md) makes loyalty, subscriptions and advanced reporting Enterprise capabilities. V32 snapshots loyalty eligibility on each new order and preserves pre-boundary promises. Payment settlement, existing points redemption/refund, committed idempotent adjustment replay and subscription cancellation remain available after expiry. New awards/adjustments, new/reactivated subscription contracts and advanced reporting are checked at the API/use-case boundary. [Reporting](reporting/reporting-v1.md) separates currency-scoped Community tender/activity metrics from paid ranking/lifetime metrics. Active tax-rate periods are constrained against overlap per jurisdiction and category, with half-open adjacent periods allowed. Backoffice exposes edition/status/import/refresh and license-aware reporting; automated component tests cover the rendered license and report workflows.

HTTP request quotas retain the production defaults of 100 requests per IP per minute and a shared 1000-request API-key budget. Redis outages fall back to local minute windows; expired fallback entries are pruned as traffic advances to a new minute. Denials use the common versioned `ApiError` envelope with `RATE_LIMITED` and `Retry-After`. Aggregate business integration fixtures use a larger test-only IP quota; dedicated tests retain quota, API-key hashing, Redis-failure and minute-reset coverage.

Credentials and provider secrets are injected/stored through an operator-controlled secret boundary, redacted from logs/telemetry/errors/exports and rotated by a documented process. TLS applies at actual deployment boundaries. Webhooks require signature verification, timestamp/replay handling and idempotency. No PCI/GDPR/Swiss-data-protection compliance is claimed from code presence alone.

## Migration sequence and current divergence

Preserve existing modular domains, PostgreSQL adapters and API behavior when compatible. The project owner confirmed that no production or other external data ever existed. SaaS tenancy is outside the product boundary; V10 defines the applicable single-installation identity/security and commerce schema. Legacy external database preservation is not a requirement.

The provider-neutral `payment.provider.v1` port now owns checkout, cancellation, capture, refund, webhook verification and reconciliation boundaries. Stripe-specific HTTP, credentials, webhook signature code and CSP permission have been removed from Core. Stripe, PayPal and PostFinance Checkout implementations are separately sourced Docker plugins; PostFinance uses its official REST SDK, hosted payment page, signed `X-Signature` webhook verification, reconciliation and online refunds. Provider webhook headers are explicitly allowlisted by Core and only the original signed body is forwarded. Restricted Docker provider contract/runtime acceptance and PostgreSQL/Core header-forwarding integration passed. Release acceptance still requires publishing immutable plugin images and running the owner-approved Stripe test-account and PostFinance Preview-space charge/refund/webhook checks when credentials are available. Those live-provider checks are explicitly deferred; local tests do not claim real transactions.

Checkout persists a payment attempt and stable provider/account namespace before invoking a PSP. Verified captures are recorded in an immutable settlement ledger, while `payments.amount` keeps the requested obligation and `captured_amount`/`captured_currency` keep the provider's actual financial result. A late capture, second successful attempt, overpayment, underpayment or currency mismatch creates an operator reconciliation case. `GET /api/v1/admin/payments/reconciliation-cases` exposes open cases; an administrator can refund against the actual captured amount, and a full refund resolves that payment's open cases. Review cases prevent an underpaid or currency-mismatched obligation from starting another checkout.

The settlement ledger also records disputes and provider reversals. Provider transaction references let dispute events correlate to a checkout even when the provider's event reference is a separate dispute ID. Open disputes pause refunds for the affected payment; wins resolve only their corresponding case, while losses and reversals reduce the refundable balance and stay visible for reconciliation. Reports keep captured, refunded, reversed, and net value distinct. The development schema remains the current V1 clean-install snapshot until production upgrade history is introduced.

## Verification required before claiming a boundary

- Architecture dependency checker passes and fails when a domain takes an adapter/framework dependency.
- Clean and upgrade PostgreSQL migration tests pass.
- Extension contract tests validate manifests, compatibility, config schema, authentication, deadlines, health, secret redaction and lifecycle transitions.
- Real container test demonstrates install/configure/enable/invoke/disable/update-or-rollback and plugin crash/timeout isolation without rebuilding Core.
- API and Backoffice authorization tests include cross-user/unauthenticated access and saved-secret non-disclosure.
- Locale/content tests prove DE/IT/FR/EN independent storage, fallback, format and search behavior.
