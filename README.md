# Artio Commerce

Artio is a self-hosted commerce platform built with Java 21, Spring Boot, PostgreSQL, Apache Kafka and Angular 17. Kafka is part of every standard installation and carries durable asynchronous events from the transactional PostgreSQL outbox. Redis and OpenSearch remain optional integrations; provider-specific implementations are moving behind Docker plugin contracts. The Gradle project is a modular monolith: bounded contexts have separate domain and persistence modules, with a REST application coordinating transactional workflows. Configurable bundles remain their own aggregate and are quoted from current component prices.

> **Architecture status:** this README describes the current checkout, not full product readiness. The target is a self-hosted single-merchant installation with an included Backoffice, optional Storefront, one Switzerland distribution, and Docker-only plugins. Current package gaps include provider extraction, distribution plugin provisioning, zero-config production secrets/key lifecycle, and the separate lifecycle controller. See [the target architecture](docs/ARCHITECTURE.md), [repository audit](docs/REPOSITORY_AUDIT.md), and [phase/task ledger](docs/ROADMAP.md).

## Local development

Install Docker Compose. The default installation generates unique database, JWT, AES-GCM and first-run setup credentials into a restricted persistent Docker volume. No `.env` file is required for the default stack. To inspect the generated setup token for the wizard, run this from the repository root and paste the output into the first-run wizard; the token is not written to logs or returned by the API:

```shell
docker compose exec rest-api cat /run/artio-secrets/setup-token
```

Installation credentials are generated once and persist with the `artio-installation-secrets` volume. Back up that volume together with PostgreSQL; losing the AES key makes encrypted plugin secrets unrecoverable. Follow the [backup and recovery procedure](docs/operations/backup-recovery.md). Optional `.env` values can override generated credentials on the first boot only. Never commit `.env`.

For IntelliJ development, run the API with `SPRING_PROFILES_ACTIVE=dev`. The `dev` profile supplies local-only credentials so the API can run without optional plugins. Set `ARTIO_SETUP_SKIP=true` to initialize the local SUPERADMIN automatically; its local-only login is `admin@artio.local` / `ArtioDevOnly!ChangeMe2026`. The skip is rejected unless `dev` is active and is always rejected when `production` is active. Omit the skip flag to use the setup wizard. Never reuse these values outside a local development environment.

```shell
docker compose up -d
```

The Backoffice is at `http://localhost:4201` and the API at `http://localhost:8080`; PostgreSQL and a one-shot credential initializer are the only required services. Flyway applies migrations at API startup. On a fresh database, the Backoffice opens the resumable wizard for the default Switzerland settings. The setup token hash is stored in PostgreSQL; the token itself stays in the protected installation volume. Drafts contain no password or integration secrets and are cleared on completion. Customer signup remains disabled until the initial SUPERADMIN exists. Five failed tokens trigger a 15-minute lock. Order confirmation emails are queued durably but sending is disabled by default. The production wizard still requires explicit owner sign-in data; provider credentials are optional.

On Windows, double-click [`Start-Artio.cmd`](Start-Artio.cmd) to build and start the default Artio platform, then open Backoffice at `http://localhost:4201`. Docker Desktop must be running. The customer Storefront is optional and is not started by the standard installation. Double-click [`Stop-Artio.cmd`](Stop-Artio.cmd) to stop the services while keeping database, Kafka and installation data.

Kafka starts automatically as a required KRaft service, persists its log data in a named Docker volume, and initializes the versioned order-events topic before the API starts. Redis, OpenSearch and the Storefront are opt-in Compose profiles (`cache`, `search`, and `storefront`). For example, enable search with `--profile search` and `ARTIO_SEARCH_ENABLED=true`; set a unique `ARTIO_OPENSEARCH_INITIAL_ADMIN_PASSWORD` in `.env` before enabling the service.

### Optional observability plugin pack

The Artio API has no Prometheus registry, Prometheus endpoint, or Grafana dependency. When observability is enabled, Core emits standard OTLP metrics and traces to an OpenTelemetry Collector. Prometheus, Grafana, and Jaeger are packaged separately under `artio-docker/observability` and are not started by the base stack. To enable the add-on, copy `artio-docker/observability/.env.example` to `artio-docker/observability/.env`, replace its password, and start the compose stack with both env files:

```shell
docker compose --env-file .env --env-file artio-docker/observability/.env \
  -f artio-docker/docker-compose.yml -f artio-docker/docker-compose.observability.yml up --build
```

Grafana, Prometheus, and Jaeger are bound to localhost on ports 3000, 9090, and 16686. Disable and remove the add-on containers with `docker compose ... down`; its data volumes can be removed separately when the operator intentionally wants to discard telemetry history. The current plugin manager lifecycle controls individual extension containers; a multi-container observability pack still needs a bundle lifecycle adapter before it can be installed and removed from Backoffice as one plugin.

Set Stripe test credentials and forward Stripe's `checkout.session.completed` and `checkout.session.expired` events to `POST /api/v1/payments/stripe/webhook` to exercise payment confirmation and reservation release. SMTP is optional; set `ARTIO_SMTP_HOST`, `ARTIO_SMTP_PORT`, credentials, and TLS options to send order email. In-app notifications are persisted even without SMTP.

## Build and verification

The Gradle wrapper builds Java modules and both locked Angular applications:

```shell
./gradlew clean build
./gradlew integrationTest
```

The API, domain unit tests, JaCoCo reports, and production Angular builds run as part of `build`. PostgreSQL integration tests use Testcontainers; a working Docker daemon is required for those tests. Frontend applications can also be built separately with `npm ci && npm run build` in `artio-storefront` and `artio-backoffice`.

Gradle plugins and Java dependencies resolve from Maven Central. npm dependencies are pinned through each Angular application's checked-in lockfile. GitHub Actions builds and verifies all modules, then builds the API and frontend container images. Publishing on `main` is enabled when `DOCKER_USERNAME` and `DOCKER_PASSWORD` repository secrets are configured.

## Modules

- Shared primitives: `artio-kernel`; generic unused persistence/event/observability abstractions were removed, while monitoring deployment assets moved to the separately packaged Docker add-on.
- Domains: `artio-catalog`, `artio-pricing`, `artio-inventory`, `artio-bundles`, `artio-orders`, `artio-users`, `artio-payments`, `artio-notifications`
- Adapters: PostgreSQL modules for catalog, pricing, inventory and bundles; OpenSearch catalog adapter
- Applications: `artio-rest-api`, `artio-storefront`, `artio-backoffice`
- Runtime and deployment: `artio-docker` (Docker Compose)

## Authentication and access

The setup API is versioned under `/api/v1/setup`: `GET /status`, `POST /resume`, `POST /progress`, and `POST /complete`. The wizard sends `X-Artio-Setup-Token`; only the token hash is stored. The status endpoint does not expose the draft; the token-protected resume endpoint returns only non-secret draft fields. In a fresh installation, customer registration is unavailable until the first administrator is created. Afterwards register a customer with `POST /api/v1/auth/register` using `{"email":"shopper@example.com","password":"at-least-12-characters","firstName":"Ada","lastName":"Lovelace"}`. Log in at `POST /api/v1/auth/login`; use the returned bearer token for cart, checkout, orders, and notifications. Passwords are BCrypt hashes. Access tokens are signed HS256 JWTs and expire after one hour.

Catalog, pricing, inventory, bundle administration, reporting, and user-management mutations require an `ADMIN` or `SUPERADMIN` role. The first-run wizard creates the initial `SUPERADMIN`. User registration always creates a `CUSTOMER`; only a superadmin can grant roles through `PUT /api/v1/admin/users/{id}/role`. Rate limiting allows 100 requests per minute per IP and 1,000 per minute for a supplied `X-API-Key`, using Redis with a local fallback. Redis-backed caching is also optional; the default standalone process uses a local in-memory cache.

## Catalog and search API

- `GET /api/v1/products`, `GET /api/v1/products/{id}`, and `GET /api/v1/products/search?q=studio&limit=20&locale=de`
- `GET /api/v1/products/{id}/localized?locale=de` resolves translated product fields using requested locale → English → base copy. Admins manage a locale with `PUT /api/v1/products/{id}/translations/{locale}`; supported locales are `de`, `it`, `fr`, and `en`.
- `POST /api/v1/products`, `PUT /api/v1/products/{id}`, `PUT /api/v1/products/{id}/active`
- `GET/POST /api/v1/products/{productId}/variants`; `GET /api/v1/variants/{id}`
- `GET/POST /api/v1/categories`, `GET/POST /api/v1/brands`, and `GET/POST /api/v1/attribute-definitions`
- `PUT /api/v1/products/{id}/catalog-metadata` assigns a brand, categories, and validated attributes

Product request: `{"name":"Laptop","description":"Portable computer","sku":"LAP-0001"}`. Variant request: `{"name":"Standard","sku":"LAP-0001-STD"}`. Search indexing is optional locally and enabled in Compose.

## Pricing and inventory

Prices are scoped to a sellable UUID, market and channel. A sellable is normally a product variant. Administrators set whether each price is tax-inclusive; inclusive prices preserve the customer-facing gross amount while Core extracts its taxable base and tax after discounts. The API applies active discounts and jurisdiction tax on the server. Storefront requests cannot supply their own unit prices or tax amounts. Product data is cached in Redis for two hours and price quotes for one hour; changes to products, prices, discounts or taxes invalidate the relevant cache.

- `GET/POST /api/v1/prices`, `PUT /api/v1/prices/{id}`, `PUT /api/v1/prices/{id}/active`
- `GET/POST /api/v1/discount-rules`, `GET/POST /api/v1/tax-rules`
- `POST /api/v1/pricing/quote` with sellable, market, channel, jurisdiction and quantity
- `GET/POST /api/v1/warehouses`, stock receipt/adjustment routes under `/api/v1/warehouses/{id}/stock`
- `POST /api/v1/inventory/reservations`, `GET /api/v1/orders/{orderId}/inventory-reservations`, and reservation allocate/release routes
- Reservations default to a 30-minute TTL (`ARTIO_INVENTORY_RESERVATION_TTL`, ISO-8601 duration). A bounded scheduled task releases expired stock; the interval is configurable with `ARTIO_INVENTORY_EXPIRY_INTERVAL_MS`.

Extension manifest preflight is available to administrators at `POST /api/v1/admin/extensions/validate`. Configure trusted registry hosts with comma-separated `ARTIO_EXTENSION_TRUSTED_REGISTRIES`. An empty value trusts no image registry. Standard Compose routes plugin lifecycle operations through a separately started `extension-controller`; only that service mounts the Docker socket, while the public REST API does not. The controller exposes no host port and accepts only authenticated, narrow lifecycle requests on the internal Compose network. Set `ARTIO_EXTENSION_DOCKER_ENABLED=false` to disable plugin runtime calls. The adapter supports digest-pinned image pull, restricted container start/stop, readiness and health checks, and a bounded authenticated invocation envelope on the `artio-extensions` internal network. It retries one transient 502/503/504 response with the same idempotency key within the original deadline; plugins must deduplicate that key. Backoffice exposes schema-backed configuration and install/enable/disable/health/update/rollback/uninstall operations. `scripts/verify-extension-runtime.ps1` runs a real reference-image lifecycle test. The observability distribution is still not installed and managed from Backoffice as a plugin bundle. The controller's Docker socket access remains equivalent to host administration. See [Extension controller API v1](docs/extensions/controller-api-v1.md) for its internal routes, credentials, network and error contract.

Create an active warehouse, receive stock for each variant and create an active price before testing checkout. Checkout refreshes all prices, calculates taxes, reserves stock, creates a pending order and requests a Stripe Checkout session. It requires an `Idempotency-Key`; replay returns the original order. Stripe session creation uses a stable order-scoped idempotency key and a 31-minute provider expiry. Immediately before session creation, the API extends all active stock holds through the session window plus a one-minute safety margin under the stock-row locks. Stripe's signed completion event marks the order paid; an expired session releases reservations early. Administrators can ship paid orders and record a tracking number through `/api/v1/admin/orders/{id}/ship`.

If Stripe webhook delivery is delayed or lost, the API reconciles pending sessions after their expected 31-minute lifetime. The bounded worker claims work with PostgreSQL `SKIP LOCKED`, queries Stripe outside the database transaction, and applies paid/expired outcomes under the order lock. Transient failures retry with exponential backoff capped at one hour; `payments.reconciliation_last_error` stores only a bounded error type. Polling defaults to 60 seconds and can be tuned with `ARTIO_PAYMENTS_RECONCILIATION_INTERVAL_MS`.

Processed Stripe webhook deduplication receipts are retained for 90 days by default and then removed in bounded batches. The retention interval and daily UTC cleanup schedule can be configured with `ARTIO_PAYMENTS_WEBHOOK_RECEIPT_RETENTION_DAYS` and `ARTIO_PAYMENTS_WEBHOOK_RECEIPT_RETENTION_CRON`. Unprocessed receipts are retained regardless of age.

Administrators can inspect safe aggregate delivery counts at `GET /api/v1/admin/operations/outbox`. It reports pending and stale event/email claims, dead email deliveries, and webhook receipts awaiting processing or past retention; it never returns queued payloads, email content, or provider error text.

Orders preserve the checkout shipping country, market, tax jurisdiction and a versioned per-line tax snapshot including applied rate/effective period, discounts, tax-inclusive mode, currency and quote timestamp. Historical orders predating this snapshot are marked as having unknown policy provenance; their stored totals are not rewritten.

## Configurable bundles

Administrators can store DE/IT/FR/EN bundle names and option-group labels with `PUT /api/v1/bundles/{id}/translations/{locale}`. Consumers can request resolved labels from `GET /api/v1/bundles/localized?locale=de` or `GET /api/v1/bundles/{id}/localized?locale=de`; resolution uses requested locale, then English, then the bundle's base labels.

`POST /api/v1/bundles` creates a fixed or configurable bundle. A configurable bundle defines option groups and choice sellables with minimum/maximum selection rules. `POST /api/v1/bundles/{id}/configure` validates selections and returns merged inventory requirements. `POST /api/v1/bundles/{id}/quote` accepts selected options and market/channel/jurisdiction, then retrieves component prices from the pricing application; callers cannot inject price values. Pricing strategies support component sum, fixed price and percentage off. The Angular storefront lets customers configure, quote and add bundles to their cart. Checkout revalidates the choices and price, reserves the underlying component stock, and stores each component with its originating bundle ID in order lines.

## Storefront, backoffice and operations

The storefront includes responsive catalog browsing, search, bundle configuration, account registration/login, cart, Stripe checkout, order history and order-status views. The backoffice includes product/variant management, bundle setup, price management, customer activation/email-verification, reporting summaries and shipment updates. Both are strict-mode Angular applications with responsive layouts and dark-mode support.

The default Compose stack starts PostgreSQL, Apache Kafka, the REST API and Backoffice. Redis, OpenSearch and the optional Storefront use opt-in profiles; observability remains a separately managed add-on pack. Docker Compose is the supported deployment path.

## API examples

The REST surface is generated as OpenAPI at `http://localhost:8080/v3/api-docs` (also served through the frontend reverse proxy). Use `/api/v1` as the canonical versioned API. Unversioned `/api` routes remain compatibility aliases. API errors use a stable JSON envelope with timestamp, HTTP status, reason, safe message, and request path.

Price request:

```json
{"sellableId":"<variant-uuid>","market":"US","channel":"WEB","amount":129.99,"currency":"USD","validFrom":"2026-01-01T00:00:00Z"}
```

Pricing quote:

```json
{"sellableId":"<variant-uuid>","market":"US","channel":"WEB","jurisdiction":"US-CA","quantity":2}
```

Add to cart with a bearer token:

```json
{"variantId":"<variant-uuid>","quantity":1,"market":"US","channel":"WEB","jurisdiction":"US-CA"}
```

The complete API is available through Spring MVC and described by the controller routes in `artio-rest-api`.
