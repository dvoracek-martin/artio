# Artio Commerce

Artio is a modular commerce platform built with Java 21, Spring Boot, PostgreSQL, Redis, Kafka, OpenSearch and Angular 17. The Gradle project is a modular monolith: bounded contexts have separate domain and persistence modules, with a single REST application coordinating workflows. Configurable bundles remain their own aggregate and are quoted from current component prices.

## Local development

Install Java 21, Node.js 20 and Docker Compose. Create a `.env` file beside the compose file (or export these values) before starting the stack:

```dotenv
ARTIO_JWT_SECRET=replace-with-a-random-secret-at-least-32-bytes-long
ARTIO_BOOTSTRAP_ADMIN_EMAIL=admin@example.com
ARTIO_BOOTSTRAP_ADMIN_PASSWORD=choose-a-unique-password-at-least-16-characters
ARTIO_STRIPE_SECRET_KEY=sk_test_...
ARTIO_STRIPE_WEBHOOK_SECRET=whsec_...
```

Then start the API and dependencies:

```shell
docker compose -f artio-docker/docker-compose.yml up --build
```

The storefront is at `http://localhost:4200`, the backoffice at `http://localhost:4201`, the API at `http://localhost:8080`, OpenSearch at `http://localhost:9200`, PostgreSQL at `localhost:5432`, Redis at `localhost:6379`, and Kafka at `localhost:9092`. Flyway applies the migrations at API startup. The bootstrap administrator is created only if both bootstrap values are supplied; the password must be at least 16 characters. Do not use the development defaults in a deployed environment.

Set Stripe test credentials and forward Stripe's `checkout.session.completed` and `checkout.session.expired` events to `POST /api/payments/stripe/webhook` to exercise payment confirmation and reservation release. SMTP is optional; set `ARTIO_SMTP_HOST`, `ARTIO_SMTP_PORT`, credentials, and TLS options to send order email. In-app notifications are persisted even without SMTP.

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
- Runtime and deployment: `artio-docker`, `artio-kubernetes`

## Authentication and access

Register a customer with `POST /api/auth/register` using `{"email":"shopper@example.com","password":"at-least-12-characters","firstName":"Ada","lastName":"Lovelace"}`. Log in at `POST /api/auth/login`; use the returned bearer token for cart, checkout, orders, and notifications. Passwords are BCrypt hashes. Access tokens are signed HS256 JWTs and expire after one hour.

Catalog, pricing, inventory, bundle administration, reporting, and user-management mutations require an `ADMIN` or `SUPERADMIN` role. The optional bootstrap account receives `SUPERADMIN`. User registration always creates a `CUSTOMER`; only a superadmin can grant roles through `PUT /api/admin/users/{id}/role`. Rate limiting allows 100 requests per minute per IP and 1,000 per minute for a supplied `X-API-Key`, using Redis with a local fallback if Redis is unavailable.

## Catalog and search API

- `GET /api/products`, `GET /api/products/{id}`, and `GET /api/products/search?q=studio&limit=20`
- `POST /api/products`, `PUT /api/products/{id}`, `PUT /api/products/{id}/active`
- `GET/POST /api/products/{productId}/variants`; `GET /api/variants/{id}`
- `GET/POST /api/categories`, `GET/POST /api/brands`, and `GET/POST /api/attribute-definitions`
- `PUT /api/products/{id}/catalog-metadata` assigns a brand, categories, and validated attributes

Product request: `{"name":"Laptop","description":"Portable computer","sku":"LAP-0001"}`. Variant request: `{"name":"Standard","sku":"LAP-0001-STD"}`. Search indexing is optional locally and enabled in Compose.

## Pricing and inventory

Prices are scoped to a sellable UUID, market and channel. A sellable is normally a product variant. The API applies active discounts and jurisdiction tax on the server. Storefront requests cannot supply their own unit prices or tax amounts. Product data is cached in Redis for two hours and price quotes for one hour; changes to products, prices, discounts or taxes invalidate the relevant cache.

- `GET/POST /api/prices`, `PUT /api/prices/{id}`, `PUT /api/prices/{id}/active`
- `GET/POST /api/discount-rules`, `GET/POST /api/tax-rules`
- `POST /api/pricing/quote` with sellable, market, channel, jurisdiction and quantity
- `GET/POST /api/warehouses`, stock receipt/adjustment routes under `/api/warehouses/{id}/stock`
- `POST /api/inventory/reservations`, `GET /api/orders/{orderId}/inventory-reservations`, and reservation allocate/release routes

Create an active warehouse, receive stock for each variant and create an active price before testing checkout. Checkout refreshes all prices, calculates taxes, reserves stock, creates a pending order and requests a Stripe Checkout session. Stripe's signed completion event marks it paid; an expired session releases reservations. Administrators can ship paid orders and record a tracking number through `/api/admin/orders/{id}/ship`.

## Configurable bundles

`POST /api/bundles` creates a fixed or configurable bundle. A configurable bundle defines option groups and choice sellables with minimum/maximum selection rules. `POST /api/bundles/{id}/configure` validates selections and returns merged inventory requirements. `POST /api/bundles/{id}/quote` accepts selected options and market/channel/jurisdiction, then retrieves component prices from the pricing application; callers cannot inject price values. Pricing strategies support component sum, fixed price and percentage off. The Angular storefront lets customers configure, quote and add bundles to their cart. Checkout revalidates the choices and price, reserves the underlying component stock, and stores each component with its originating bundle ID in order lines.

## Storefront, backoffice and operations

The storefront includes responsive catalog browsing, search, bundle configuration, account registration/login, cart, Stripe checkout, order history and order-status views. The backoffice includes product/variant management, bundle setup, price management, customer activation/email-verification, reporting summaries and shipment updates. Both are strict-mode Angular applications with responsive layouts and dark-mode support.

Compose starts PostgreSQL, Redis, Kafka in single-node KRaft mode, OpenSearch, the REST API and both Angular frontends. Kubernetes manifests in `artio-kubernetes` deploy the API and frontends; PostgreSQL, Redis, Kafka and OpenSearch are expected to be provided by the cluster or managed services. Supply the `artio-secrets` Secret and replace the example domains and image tags before deployment.

## API examples

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
