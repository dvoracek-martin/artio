# Artio Commerce

Artio is a self-hosted commerce platform built with Java 25 LTS, Spring Boot 4.1.1, PostgreSQL, Apache Kafka and Angular 22. The backend builds with Gradle 9.8.1; the web clients use TypeScript 6, Node.js 24.22.0 LTS and npm 12.2.0. `.nvmrc` and the frontend container build pin the JavaScript runtime baseline. Kafka is part of every standard installation and carries durable asynchronous events from the transactional PostgreSQL outbox. Redis and OpenSearch remain optional integrations; payments, shipping and notification delivery use Docker plugin contracts. The Gradle project is a modular monolith: bounded contexts have separate domain and persistence modules, with a REST application coordinating transactional workflows. Configurable bundles remain their own aggregate and are quoted from current component prices.

> **Architecture status:** this README describes the current checkout, not full product readiness. The target is a self-hosted single-merchant installation with an included Backoffice, optional Storefront, one Switzerland distribution, and Docker-only plugins. Current release gaps include published immutable plugin image digests, live verification of the isolated lifecycle controller and provider workflows, Docker-backed database acceptance, and production restore rehearsal. Versioned encryption envelopes and a SUPERADMIN rewrap operation are implemented; deployment and restore acceptance remain. See [the target architecture](docs/ARCHITECTURE.md), [repository audit](docs/REPOSITORY_AUDIT.md), and [phase/task ledger](docs/ROADMAP.md).

## Local development

Install Docker Compose. The default installation generates unique database, JWT, AES-GCM and first-run setup credentials into a restricted persistent Docker volume. No `.env` file is required for the default stack. To inspect the generated setup token for the wizard, run this from the repository root and paste the output into the first-run wizard; the token is not written to logs or returned by the API:

```shell
docker compose exec rest-api cat /run/artio-secrets/setup-token
```

Installation credentials are generated once and persist with the `artio-installation-secrets` volume. Back up that volume, PostgreSQL, and the `artio-catalog-media` volume together; otherwise encrypted plugin credentials or product image blobs may be lost. Plugin authentication uses a separate stable key so AES rotation does not reset installed plugins. Follow the [backup, recovery and key rotation procedure](docs/operations/backup-recovery.md). Optional `.env` values can override generated credentials on the first boot only. Never commit `.env`.

Artio is proprietary software with a useful free Community edition. Enterprise covers loyalty, subscriptions and advanced reporting. The [edition matrix and signed entitlement contract](docs/licensing/entitlements-v1.md) define the boundary and preservation of existing obligations. Local Ed25519 verification, PostgreSQL license state, owner-only status/import and optional bounded scheduled HTTPS refresh are implemented; server-side loyalty/subscription/reporting gates preserve existing obligations. Backoffice license/report workflows are still being completed. See the [currency-separated reporting contract](docs/reporting/reporting-v1.md). Community needs no issuer configuration.

For local Backoffice development with the setup wizard, run [`Start-Artio.cmd`](Start-Artio.cmd). It sets `ARTIO_API_SPRING_PROFILES_ACTIVE=dev` for the API while keeping worker profiles unchanged, and always leaves first-run setup enabled. When the database has no administrator, the wizard prefills its local setup token, demo administrator identity and password. The local-only setup token is `ArtioLocalDevSetupTokenOnly2026!`; after completing the wizard, sign in with `admin@example.test` / `ArtioLocalAdmin!2026`. Developer mode skips privileged MFA enrollment to keep local setup one-click; the default and production profiles require MFA. These predictable credentials are rejected whenever the production profile is active. For IntelliJ development, run the API with `SPRING_PROFILES_ACTIVE=dev`. Never reuse these values outside a local development environment.

For one-click local startup of the full stack, double-click [`Start-Artio-With-Storefront.cmd`](Start-Artio-With-Storefront.cmd) in Windows. It starts the API, Backoffice and Storefront in Docker under the `dev` profile, waits for them to become ready, then opens both frontends. A fresh database always opens the first-run wizard, even if the browser has a stale session token. Complete it with the prefilled local SUPERADMIN `admin@example.test` / `ArtioLocalAdmin!2026`. Double-click [`Stop-Artio.cmd`](Stop-Artio.cmd) to stop every Artio service and remove its containers/network while keeping database, secrets and media volumes.

```shell
docker compose up -d
```

The Backoffice is at `http://localhost:4201` and the API at `http://localhost:8080`. PostgreSQL and Kafka are required; a one-shot initializer creates persistent installation credentials before PostgreSQL starts. Flyway applies the single current development schema snapshot at API startup. Pre-production database upgrades are not supported; production releases will add a versioned upgrade path. On a fresh database, the Backoffice opens the resumable wizard for the default Switzerland settings. The setup token hash is stored in PostgreSQL; the token itself stays in the protected installation volume. Drafts contain no password or integration secrets and are cleared on completion. Customer signup remains disabled until the initial SUPERADMIN exists. Five failed tokens trigger a 15-minute lock. Order confirmation emails are queued durably but sending is disabled by default. The production wizard still requires explicit owner sign-in data; provider credentials are optional.

On Windows, double-click [`Start-Artio.cmd`](Start-Artio.cmd) to build and start the standard Artio platform with Backoffice. Docker Desktop must be running. This keeps the customer Storefront optional. To start the development stack with both user interfaces and the local SUPERADMIN, use [`Start-Artio-With-Storefront.cmd`](Start-Artio-With-Storefront.cmd); it opens Backoffice at `http://localhost:4201` and Storefront at `http://localhost:4200`. Double-click [`Stop-Artio.cmd`](Stop-Artio.cmd) to stop all services while keeping database, Kafka and installation data.

Kafka starts automatically as a required KRaft service, persists its log data in a named Docker volume, and initializes the versioned order-events topic before the API starts. Redis and the Storefront are opt-in Compose profiles (`cache` and `storefront`). Search is disabled by default and, when enabled, uses the managed `catalog.search.v1` plugin configured with `ARTIO_SEARCH_PROVIDER=extension` and `ARTIO_SEARCH_EXTENSION_PLUGIN_ID=catalog.opensearch`. The OpenSearch plugin owns its private OpenSearch sidecar and persistent index data. Its source is in [the OpenSearch search plugin package](artio-plugins/opensearch-search/README.md); the manifest remains unavailable until its images are published with verified digests. The direct OpenSearch adapter is used only in the `artio-catalog-opensearch` Testcontainers integration tests and is not included in the production API image.

### Optional observability plugin pack

The Artio API has no Prometheus registry, Prometheus endpoint, or Grafana dependency. When observability is enabled, Core emits standard OTLP metrics and traces through an authenticated, allowlisted lifecycle-controller route to an OpenTelemetry Collector. The separately installable plugin source pack is in `artio-plugins/observability`; the Compose overlay under `artio-docker/observability` remains useful for local development and operator-managed deployment. The plugin manifest is a template with unpublished Artio image digests, so it is not yet installable from a registry. To run the Compose add-on, copy `artio-docker/observability/.env.example` to `artio-docker/observability/.env`, replace its password, and start the compose stack with both env files:

```shell
docker compose --env-file .env --env-file artio-docker/observability/.env \
  -f artio-docker/docker-compose.yml -f artio-docker/docker-compose.observability.yml up --build
```

Grafana, Prometheus, and Jaeger are bound to localhost on ports 3000, 9090, and 16686. Disable and remove the add-on containers with `docker compose ... down`; its data volumes can be removed separately when the operator intentionally wants to discard telemetry history. The Plugin Manager supports install, configure, enable, disable, update, rollback and uninstall for a registered plugin, and Backoffice opens Grafana through an authenticated read-only same-origin proxy. Actual registry image publication and Docker-backed lifecycle acceptance remain open.

Payment provider integrations run as Docker plugins. Core defaults to no payment provider; after installing and configuring a provider plugin, set `ARTIO_PAYMENT_PROVIDER=extension` and `ARTIO_PAYMENTS_EXTENSION_PLUGIN_ID` to its plugin ID (for example `payments.stripe`). Configure the provider's signed webhook to `POST /api/v1/payments/provider/webhook`; checkout, cancellation, refunds, signature verification and reconciliation use the documented `payment.provider.v1` contract. Stripe, PayPal and PostFinance Checkout source packages are available under `artio-plugins`; their images are not yet published, so they cannot be installed from a registry. PostFinance uses the official Checkout REST SDK and hosted payment page; see `artio-plugins/postfinance-payments/README.md` for its encrypted settings, webhook setup, and local checks. Its live Preview-space charge/refund acceptance still needs the owner-selected sandbox account to be configured in Artio's local development environment. Email delivery is disabled by default; notification events remain durable in the outbox. To deliver email, enable the Brevo Docker plugin and set `ARTIO_EMAIL_ENABLED=true`, `ARTIO_EMAIL_PROVIDER=extension`, and `ARTIO_NOTIFICATIONS_EXTENSION_PLUGIN_ID=notifications.brevo`; configure its verified sender and API key in Plugin Manager. Core contains no SMTP client or provider credentials.

The Swiss Post shipping plugin source is under `artio-plugins/swiss-post`. It supports domestic service quotes using merchant-configured CHF rates and label generation through the official Barcode API. Checkout discovers and revalidates the selected delivery service, then Backoffice can create the provider shipment and retrieve its PDF label. Enable it with `ARTIO_SHIPPING_PROVIDER=extension` and `ARTIO_SHIPPING_EXTENSION_PLUGIN_ID=shipping.swiss-post` after installing/configuring the plugin. Swiss Post client registration, the merchant franking licence and API entitlement are required; tracking is not supported without a separate entitlement. The plugin image has not been published and its digest is intentionally a template. See its [setup and capability notes](artio-plugins/swiss-post/README.md).

## Build and verification

The Gradle wrapper builds Java modules and both locked Angular applications:

```shell
./gradlew clean build
./gradlew integrationTest
```

The API, domain unit tests, JaCoCo reports, and production Angular builds run as part of `build`. PostgreSQL integration tests use Testcontainers; a working Docker daemon is required for those tests. Frontend applications can also be built separately with `npm ci && npm run build` in `artio-storefront` and `artio-backoffice`.

Gradle plugins and Java dependencies resolve from Maven Central. npm dependencies are pinned through each Angular application's checked-in lockfile. GitHub Actions builds and verifies all modules and plugin source tests, then builds the API, frontends and Swiss Post plugin container locally in the runner. The workflow does not publish images or source artifacts.

## Modules

- Shared primitives: `artio-kernel`; generic unused persistence/event/observability abstractions were removed, while monitoring deployment assets moved to the separately packaged Docker add-on.
- Domains: `artio-catalog`, `artio-pricing`, `artio-inventory`, `artio-bundles`, `artio-orders`, `artio-users`, `artio-payments`, `artio-notifications`
- Adapters: PostgreSQL modules for catalog, pricing, inventory and bundles; OpenSearch catalog adapter
- Applications: `artio-rest-api`, `artio-storefront`, `artio-backoffice`
- Runtime and deployment: `artio-docker` (Docker Compose)

## Authentication and access

The setup API is versioned under `/api/v1/setup`: `GET /status`, `POST /resume`, `POST /progress`, and `POST /complete`. The wizard sends `X-Artio-Setup-Token`; only the token hash is stored. The status endpoint does not expose the draft; the token-protected resume endpoint returns only non-secret draft fields. In a fresh installation, customer registration is unavailable until the first administrator is created. Afterwards register a customer with `POST /api/v1/auth/register` using `{"email":"shopper@example.com","password":"at-least-12-characters","firstName":"Ada","lastName":"Lovelace"}`. Log in at `POST /api/v1/auth/login`; use the returned bearer token for cart, checkout, orders, and notifications. Passwords are BCrypt hashes. Access tokens are signed HS256 JWTs and expire after one hour.

Catalog, pricing, inventory, bundle administration, reporting, and user-management mutations require an `ADMIN` or `SUPERADMIN` role. By default, privileged accounts must enroll in TOTP before protected API access; an un-enrolled first login receives a setup-only token, and the Backoffice guides enrollment and one-time backup-code storage. This requirement remains enabled in production and is disabled only by the local `dev` profile. Privileged accounts cannot disable MFA through the API. The first-run wizard creates the initial `SUPERADMIN`. User registration always creates a `CUSTOMER`; only a superadmin can grant roles through `PUT /api/v1/admin/users/{id}/role`. See [privileged MFA v1](docs/security/privileged-mfa-v1.md). Rate limiting allows 100 requests per minute per IP and 1,000 per minute for a supplied `X-API-Key`, using Redis with a local fallback. Redis-backed caching is also optional; the default standalone process uses a local in-memory cache.

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

Extension manifest preflight is available to administrators at `POST /api/v1/admin/extensions/validate`. Configure trusted registry hosts with comma-separated `ARTIO_EXTENSION_TRUSTED_REGISTRIES`. An empty value trusts no image registry. Standard Compose routes plugin lifecycle operations through a separately started `extension-controller`; only that service mounts the Docker socket, while the public REST API does not. The controller exposes no host port and accepts only authenticated, narrow lifecycle requests on the internal Compose network. Set `ARTIO_EXTENSION_DOCKER_ENABLED=false` to disable plugin runtime calls. The adapter supports digest-pinned image pull, restricted container start/stop, readiness and health checks, and a bounded authenticated invocation envelope on the `artio-extensions` internal network. It retries one transient 502/503/504 response with the same idempotency key within the original deadline; plugins must deduplicate that key. Backoffice exposes schema-backed configuration and install/enable/disable/health/update/rollback/uninstall operations. The admin Plugin Manager lists recommended releases from the regional catalog at `GET /api/v1/admin/extensions/catalog?country=CH`; unavailable releases remain marked `WAITING_FOR_RELEASE`, while retained uninstalled releases can be restored to `VALIDATED` and configured again without restoring purged secrets. `scripts/verify-extension-runtime.ps1` runs a real reference-image lifecycle test. The observability plugin source also supports a narrowly scoped authenticated Core-to-collector OTLP route and authenticated read-only Grafana access from Backoffice; verified release images and live Docker verification remain open. The controller's Docker socket access remains equivalent to host administration. See [Extension controller API v1](docs/extensions/controller-api-v1.md) for its internal routes, credentials, network and error contract.

### Marketplace plugin discovery

Set `ARTIO_MARKETPLACE_BASE_URL` to the public Artio Marketplace API origin to enable marketplace search in Backoffice. For a host-run Core API, use the Marketplace's printed local API address. When Core runs in Docker Desktop and Marketplace publishes its API port on the host, use the host-reachable address, for example `http://host.docker.internal:18080` (replace the port if Marketplace selected another one). The Plugin Manager calls only `/api/v1/marketplace/plugins` and the selected release's `/manifest` endpoint; no publisher or admin Marketplace credential is sent to Core or the browser. Search requests include Core's configured version and optional country. Registering a result fetches its original manifest and passes it through Core's current local validator. Installation remains a separate administrator action; the extension controller pulls each digest-pinned OCI image using the trusted registry host configured in `ARTIO_EXTENSION_TRUSTED_REGISTRIES`. For the Marketplace's local Docker stack, a containerized Core also needs the manifest's public registry address to be host-reachable: set Marketplace `MARKETPLACE_REGISTRY_HOST` to `host.docker.internal:<public-registry-port>` and Artio `ARTIO_EXTENSION_TRUSTED_REGISTRIES` to that same host:port. Publishers continue pushing through the separate Marketplace publisher port. Configure that public registry endpoint as insecure in Docker Desktop for local HTTP development. A Marketplace listing or compatibility claim never bypasses Core's manifest, geographic, dependency, or registry-trust checks.

Create an active warehouse, receive stock for each variant and create an active price before testing checkout. Checkout refreshes prices, calculates taxes, reserves stock and creates an idempotent order. Payment is optional and defaults to no provider; when an enabled `payment.provider.v1` plugin is configured, Core requests hosted checkout with a stable order-scoped idempotency key and bounded expiry. Reservations are extended through the provider expiry plus a safety margin. Signed provider events or reconciliation mark settlement only when reference, exact amount and currency match the persisted payment. Definitive provider rejection cancels the pending order, restores any gift-card tender and releases stock atomically; ambiguous outcomes remain retryable with the same key.

If provider webhook delivery is delayed or lost, the provider-neutral reconciliation worker claims pending payments with PostgreSQL `SKIP LOCKED`, queries the enabled plugin outside the database transaction, and applies verified paid/expired outcomes under the order lock. Transient failures retry with bounded exponential backoff and safe error-class diagnostics.

Processed provider webhook deduplication receipts are retained for a bounded period and removed in batches. Unprocessed receipts remain available for reconciliation.

Administrators can inspect safe aggregate delivery counts at `GET /api/v1/admin/operations/outbox`. It reports pending and stale event/email claims, dead email deliveries, and webhook receipts awaiting processing or past retention; it never returns queued payloads, email content, or provider error text.

Orders preserve the checkout shipping country, market, tax jurisdiction and a versioned per-line tax snapshot including applied rate/effective period, discounts, tax-inclusive mode, currency and quote timestamp. Historical orders predating this snapshot are marked as having unknown policy provenance; their stored totals are not rewritten.

## Configurable bundles

Administrators can store DE/IT/FR/EN bundle names and option-group labels with `PUT /api/v1/bundles/{id}/translations/{locale}`. Consumers can request resolved labels from `GET /api/v1/bundles/localized?locale=de` or `GET /api/v1/bundles/{id}/localized?locale=de`; resolution uses requested locale, then English, then the bundle's base labels.

`POST /api/v1/bundles` creates a fixed or configurable bundle. A configurable bundle defines option groups and choice sellables with minimum/maximum selection rules. `POST /api/v1/bundles/{id}/configure` validates selections and returns merged inventory requirements. `POST /api/v1/bundles/{id}/quote` accepts selected options and market/channel/jurisdiction, then retrieves component prices from the pricing application; callers cannot inject price values. Pricing strategies support component sum, fixed price and percentage off. The Angular storefront lets customers configure, quote and add bundles to their cart. Checkout revalidates the choices and price, reserves the underlying component stock, and stores each component with its originating bundle ID in order lines.

## Storefront, backoffice and operations

First-run setup uses a [five-step installation wizard](docs/setup/first-run-wizard.md) with token verification, store identity, regional settings, owner access, review and a success screen. [Shared Backoffice controls](docs/backoffice-ux.md) provide searchable dropdowns, multiple selections and readable product/variant choices across setup, catalog, pricing, inventory and bundles.

The shared visual language is documented in [Artio visual identity](docs/BRAND_IDENTITY.md): Sora typography, dark navy surfaces and cyan–indigo–violet accents. Backoffice product creation has a [dedicated four-step wizard](docs/catalog/backoffice-product-wizard.md) at `/products/new`, including category assignment, a first variant, image previews and a final review. Product drafts and their initial variant/classification are created atomically. Image storage supports the default persistent volume or an optional [S3-compatible private bucket](docs/catalog/product-media-v1.md#optional-s3-compatible-storage).

The storefront includes responsive catalog browsing, search, bundle configuration, account registration/login, cart, optional plugin-backed checkout, order history and order-status views. The Backoffice includes catalog, bundle, pricing, inventory, order/return/refund, customer, gift-card, regional settings, plugin lifecycle and operational delivery workflows. Both are strict-mode Angular applications with responsive layouts and dark-mode support.

Backoffice catalog administration includes paginated search, metadata, translations, SEO, CSV exchange and product media upload/replacement/order/removal. Product images are validated and stored on a persistent Docker volume by default; multi-host deployments can use the configurable S3-compatible media adapter. See [product media API v1](docs/catalog/product-media-v1.md).

Backoffice order search, detail, multi-shipment fulfillment, timeline, internal notes and CSV export use secured staff APIs. ADMIN/SUPERADMIN can issue idempotent partial or full payment refunds from order detail; customer return requests, staff review, receipt and optional warehouse restocking keep merchandise and financial history separate. See [admin order management](docs/orders/admin-order-management-v1.md), [order fulfillment](docs/orders/fulfillment-v1.md), [payment refunds](docs/payments/refunds-v1.md) and [order returns, timeline and notes](docs/orders/returns-v1.md).

Loyalty points accrue after confirmed payment, reverse proportionally with refunds and support idempotent redemption and audited staff adjustments. See [loyalty v1](docs/loyalty/loyalty-v1.md).

The default Compose stack starts PostgreSQL, Apache Kafka, the REST API, event-worker, notification-worker, extension-controller and Backoffice. Redis and OpenSearch are optional profiles; start Storefront with `docker compose --profile storefront up -d` or `Start-Artio-With-Storefront.cmd`. Observability has a local Compose overlay and a separately managed plugin source pack; its registry image digests are not yet published. Docker Compose is the supported deployment path.

The Storefront exposes `/sitemap.xml` and `/robots.txt` through Nginx. Core builds those documents using `ARTIO_STOREFRONT_URL`; set it to the public Storefront origin in a deployed environment. The local Compose default is `http://localhost:4200`. The sitemap lists the public home, shop, bundles and published product detail routes; private customer and checkout routes are excluded. Published products have localized standalone `/products/{slug}?locale={locale}` pages; inactive and archived products are unavailable publicly.

The Storefront SEO behavior and its client-rendering limits are documented in [the search rendering strategy](docs/storefront-seo.md).

## API examples

The REST surface is generated as OpenAPI at `http://localhost:8080/v3/api-docs` (also served through the frontend reverse proxy). Every REST controller uses the versioned `/api/v1` path. Unversioned `/api` routes are not part of the API contract. API errors use a stable JSON envelope with `timestamp`, `status`, `error`, `code`, `message`, and `path`. The `code` is a locale-independent client key (`BAD_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `CONFLICT`, `RATE_LIMITED`, `SERVICE_UNAVAILABLE`, `SERVER_ERROR`, or `REQUEST_FAILED`); `message` is safe diagnostic text and may be English. Clients should branch on `code`, not parse `message` or `error`.

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
