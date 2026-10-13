# Artio Commerce

Artio is a modular commerce platform built with Java 21, Spring Boot, PostgreSQL, Redis, Kafka, OpenSearch and Angular 17. The Gradle project is a modular monolith: bounded contexts have separate domain and persistence modules, with a single REST application coordinating workflows. Configurable bundles remain their own aggregate and are quoted from current component prices.

> **Architecture status:** this README describes the current checkout, not full product readiness. The binding target is a self-hosted single-merchant installation with a proprietary Community/Enterprise edition model, an included Backoffice, an optional headless Storefront, and Docker-only provider plugins. See [the target architecture](docs/ARCHITECTURE.md), [repository audit](docs/REPOSITORY_AUDIT.md), and [phase/task ledger](docs/ROADMAP.md). Current code still has documented conflicts and unverified migration/deployment behavior.

## Local development

Install Java 21, Node.js 20 and Docker Compose. Copy `.env.example` to `.env` in the repository root, replace every `replace-with-...` value with a unique secret, and keep `.env` private. The API rejects placeholder or cryptographic keys shorter than 32 bytes. Compose requires the database, JWT, encryption, and Grafana passwords instead of supplying public defaults:

```dotenv
ARTIO_DATABASE_PASSWORD=replace-with-a-unique-database-password
ARTIO_JWT_SECRET=replace-with-at-least-32-random-bytes
ARTIO_ENCRYPTION_KEY=replace-with-a-different-32-byte-or-longer-secret
ARTIO_BOOTSTRAP_ADMIN_EMAIL=admin@example.com
ARTIO_BOOTSTRAP_ADMIN_PASSWORD=choose-a-unique-password-at-least-16-characters
GRAFANA_ADMIN_PASSWORD=replace-with-unique-grafana-password
ARTIO_STRIPE_SECRET_KEY=sk_test_...
ARTIO_STRIPE_WEBHOOK_SECRET=whsec_...
```

Then start the API and dependencies:

```shell
docker compose --env-file .env -f artio-docker/docker-compose.yml up --build
```

The storefront is at `http://localhost:4200`, the backoffice at `http://localhost:4201`, the API at `http://localhost:8080`, OpenSearch at `http://localhost:9200`, PostgreSQL at `localhost:5432`, Redis at `localhost:6379`, and Kafka at `localhost:9092`. Flyway applies the migrations at API startup. The bootstrap administrator is created only if both bootstrap values are supplied; the password must be at least 16 characters. Do not use the development defaults in a deployed environment.

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

- Shared primitives and infrastructure: `artio-kernel`, `artio-persistence`, `artio-events`, `artio-observability`
- Domains: `artio-catalog`, `artio-pricing`, `artio-inventory`, `artio-bundles`, `artio-orders`, `artio-users`, `artio-payments`, `artio-notifications`
- Adapters: PostgreSQL modules for catalog, pricing, inventory and bundles; OpenSearch catalog adapter
- Applications: `artio-rest-api`, `artio-storefront`, `artio-backoffice`
- Runtime and deployment: `artio-docker` (Docker Compose)

## Authentication and access

Register a customer with `POST /api/v1/auth/register` using `{"email":"shopper@example.com","password":"at-least-12-characters","firstName":"Ada","lastName":"Lovelace"}`. Log in at `POST /api/v1/auth/login`; use the returned bearer token for cart, checkout, orders, and notifications. Passwords are BCrypt hashes. Access tokens are signed HS256 JWTs and expire after one hour.

Catalog, pricing, inventory, bundle administration, reporting, and user-management mutations require an `ADMIN` or `SUPERADMIN` role. The optional bootstrap account receives `SUPERADMIN`. User registration always creates a `CUSTOMER`; only a superadmin can grant roles through `PUT /api/v1/admin/users/{id}/role`. Rate limiting allows 100 requests per minute per IP and 1,000 per minute for a supplied `X-API-Key`, using Redis with a local fallback if Redis is unavailable.

## Catalog and search API

- `GET /api/v1/products`, `GET /api/v1/products/{id}`, and `GET /api/v1/products/search?q=studio&limit=20&locale=de`
- `GET /api/v1/products/{id}/localized?locale=de` resolves translated product fields using requested locale → English → base copy. Admins manage a locale with `PUT /api/v1/products/{id}/translations/{locale}`; supported locales are `de`, `it`, `fr`, and `en`.
- `POST /api/v1/products`, `PUT /api/v1/products/{id}`, `PUT /api/v1/products/{id}/active`
- `GET/POST /api/v1/products/{productId}/variants`; `GET /api/v1/variants/{id}`
- `GET/POST /api/v1/categories`, `GET/POST /api/v1/brands`, and `GET/POST /api/v1/attribute-definitions`
- `PUT /api/v1/products/{id}/catalog-metadata` assigns a brand, categories, and validated attributes

Product request: `{"name":"Laptop","description":"Portable computer","sku":"LAP-0001"}`. Variant request: `{"name":"Standard","sku":"LAP-0001-STD"}`. Search indexing is optional locally and enabled in Compose.

## Pricing and inventory

Prices are scoped to a sellable UUID, market and channel. A sellable is normally a product variant. The API applies active discounts and jurisdiction tax on the server. Storefront requests cannot supply their own unit prices or tax amounts. Product data is cached in Redis for two hours and price quotes for one hour; changes to products, prices, discounts or taxes invalidate the relevant cache.

- `GET/POST /api/v1/prices`, `PUT /api/v1/prices/{id}`, `PUT /api/v1/prices/{id}/active`
- `GET/POST /api/v1/discount-rules`, `GET/POST /api/v1/tax-rules`
- `POST /api/v1/pricing/quote` with sellable, market, channel, jurisdiction and quantity
- `GET/POST /api/v1/warehouses`, stock receipt/adjustment routes under `/api/v1/warehouses/{id}/stock`
- `POST /api/v1/inventory/reservations`, `GET /api/v1/orders/{orderId}/inventory-reservations`, and reservation allocate/release routes
- Reservations default to a 30-minute TTL (`ARTIO_INVENTORY_RESERVATION_TTL`, ISO-8601 duration). A bounded scheduled task releases expired stock; the interval is configurable with `ARTIO_INVENTORY_EXPIRY_INTERVAL_MS`.

Extension manifest preflight is available to administrators at `POST /api/v1/admin/extensions/validate`. Configure trusted registry hosts with comma-separated `ARTIO_EXTENSION_TRUSTED_REGISTRIES`. An empty value trusts no image registry. The endpoint validates metadata and digest pinning only; Docker installation, invocation, configuration storage and Backoffice management are not implemented yet.

Create an active warehouse, receive stock for each variant and create an active price before testing checkout. Checkout refreshes all prices, calculates taxes, reserves stock, creates a pending order and requests a Stripe Checkout session. It requires an `Idempotency-Key`; replay returns the original order. Stripe session creation uses a stable order-scoped idempotency key and a 31-minute provider expiry. Immediately before session creation, the API extends all active stock holds through the session window plus a one-minute safety margin under the stock-row locks. Stripe's signed completion event marks the order paid; an expired session releases reservations early. Administrators can ship paid orders and record a tracking number through `/api/v1/admin/orders/{id}/ship`.

Orders preserve the checkout shipping country, market, tax jurisdiction and a versioned per-line tax snapshot including applied rate/effective period, discounts, currency and quote timestamp. Historical orders predating this snapshot are marked as having unknown policy provenance; their stored totals are not rewritten.

## Configurable bundles

`POST /api/v1/bundles` creates a fixed or configurable bundle. A configurable bundle defines option groups and choice sellables with minimum/maximum selection rules. `POST /api/v1/bundles/{id}/configure` validates selections and returns merged inventory requirements. `POST /api/v1/bundles/{id}/quote` accepts selected options and market/channel/jurisdiction, then retrieves component prices from the pricing application; callers cannot inject price values. Pricing strategies support component sum, fixed price and percentage off. The Angular storefront lets customers configure, quote and add bundles to their cart. Checkout revalidates the choices and price, reserves the underlying component stock, and stores each component with its originating bundle ID in order lines.

## Storefront, backoffice and operations

The storefront includes responsive catalog browsing, search, bundle configuration, account registration/login, cart, Stripe checkout, order history and order-status views. The backoffice includes product/variant management, bundle setup, price management, customer activation/email-verification, reporting summaries and shipment updates. Both are strict-mode Angular applications with responsive layouts and dark-mode support.

Compose starts PostgreSQL, Redis, Kafka in single-node KRaft mode, OpenSearch, the REST API and both Angular frontends. Docker Compose is the supported deployment path in the current implementation.

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
