# Artio repository audit

**Audit date:** 2026-10-13
**Directive:** `ARTIO-Codex-Final-Implementation-Directive-v3.1.md` (user-provided product brief)
**Repository:** `D:\Development\Projects\Artio`
**Evidence boundary:** source/configuration and commands listed below; no production deployment or live database was available.

## Current implementation update (2026-10-14)

Directive v4.0 supersedes the previous optional-Kafka and Phase 2 stop assumptions. The active Gradle build contains 19 projects: Java domain/adapters/runtime, REST API, Backoffice, optional Storefront, and Docker packaging. Unused `artio-persistence`, `artio-events`, and the unconsumed Java portion of `artio-observability` were removed after source-reference review. Observability deployment files were moved to `artio-docker/observability`; Core uses vendor-neutral OTLP and has no Prometheus registry or Grafana/Prometheus service implementation. Redis and OpenSearch remain optional; Kafka is required in the default Compose stack. The versioned producer outbox has bounded retries and admin redrive; the event worker handles transactional publication/consumption and the notification worker independently delivers the PostgreSQL notification outbox. Consumer lag and encrypted DLQ replay are available to ADMIN through API/Backoffice. Checkout, cancellation, capture, refund, webhook verification and reconciliation use the provider-neutral `payment.provider.v1`; direct Stripe code has been removed from Core. Plugin lifecycle includes safe uninstall with preserved audit/release tombstones and purged stored configuration/secrets. Docker-backed payment plugin, Kafka, PostgreSQL, and multi-replica acceptance remain unavailable in this checkout environment.

The secure resumable First-Run Setup Wizard, generated persistent Compose credentials, setup gate, owner-only System Settings and profile-only developer mode are implemented and PostgreSQL-tested. Setup stores store identity and regional/language settings and atomically records each recommended plugin availability/lifecycle state; System Settings exposes these states and Plugin Manager remains the explicit install/configure/enable path. Published trusted image releases and live Docker provisioning remain open. Remaining Phase 2 acceptance includes publishing trusted immutable release digests for provider/search/notification/observability plugin images, live Docker lifecycle and provider verification, PostgreSQL-backed key-rotation/restore rehearsal, and production recovery acceptance. The Switzerland distribution, provider-neutral payment/shipping/notification/search/event contracts, Plugin Manager workflows and authenticated read-only Grafana proxy are implemented; do not duplicate them as unfinished work. Plugin runtime now manages optional digest-pinned sidecar services as one lifecycle bundle. The Docker socket is isolated in `extension-controller` and is not mounted in the Core API. Do not mark Phase 2 complete until all applicable acceptance gates pass. AI Frontend Builder and AI Module Builder remain **DEFERRED – future product decision** per the product owner's direct scope correction; do not create scaffolding or AI-specific architecture. The module classification, usage audit, and current-size evidence are in [module inventory](module-inventory.md).

The single V1 baseline includes setup schema formerly introduced by V26/V27. Setup acceptance is a clean migration plus a live Testcontainers lifecycle: an uninitialized database requires a 32-character setup token, only its SHA-256 digest is stored, invalid POST attempts are throttled, `/resume` only reveals non-secret draft values to the token holder, `/progress` never accepts a password or integration credential, and `/complete` transactionally creates the first SUPERADMIN and installation region before clearing the draft. Anonymous customer signup is rejected until an administrator exists. The Windows local-development launchers explicitly select the `dev` profile for the API, which activates the prefilled local-only wizard token and demo identity; `start-api.sh` refuses that deterministic token whenever `production` is active. Direct Compose deployments remain production-profile by default and use the separately generated setup token. The local integration test passed the setup flow against PostgreSQL.

The expanded feature audit found Stripe and PayPal provider Docker plugin sources and provider-neutral Core orchestration. Core now has no Stripe HTTP or webhook verification implementation; image publication and live provider acceptance remain unverified. The read-only GraphQL catalog endpoint has no Backoffice/Storefront client or dedicated tests, but remains in the public runtime and is classified **DEFER** pending product approval rather than being destructively removed. Gift-card, loyalty, subscription, deterministic recommendations/fraud, consent/privacy export/erasure and private authenticated order WebSocket routes are wired to PostgreSQL/API flows; because incompleteness alone is not a removal reason, retain them and record missing focused tests as follow-up. Evidence and classification are in [module inventory](module-inventory.md).

## Historical current implementation update (2026-10-13; superseded below)

The project owner closed `P1-02` as **closed – not applicable**: no production or other external data ever existed. V10 was therefore aligned directly to the single-installation product architecture; no legacy database compatibility or preservation migration is required. Tenant runtime context, tenant administration, JWT claims, schema, and tenant-scoped queries are removed. Migration acceptance is a clean PostgreSQL install with the single consolidated `V1__initial_schema.sql` baseline; local databases/volumes are not automatically deleted. The V9-to-V15 data-preservation test was removed because that compatibility is explicitly out of scope.

The API unit and integration tasks passed after the schema/runtime changes; clean install and no-oversell parallel reservations are proven using Docker Testcontainers. The exact commands and the remaining Kafka test teardown log are recorded in `ROADMAP.md`. Do not interpret the historical audit sections below as current statements when contradicted by this update.

The localized catalog reindex now builds a separate OpenSearch generation, refreshes it and atomically swaps the search alias. OpenSearch 2.17 Testcontainers verified replacement, stale-document removal, real alias search and rollback on replay failure. This test also caught an invalid query JSON envelope, which is fixed. Compose now requires `ARTIO_OPENSEARCH_INITIAL_ADMIN_PASSWORD`, since the pinned 2.17 image requires a bootstrap password even when its security plugin is disabled.

Phase 2 includes an opt-in Docker Engine adapter for digest-pinned installation, restricted container policy, readiness/health, install/enable/disable and a bounded authenticated v1 invocation contract. A Core-owned runtime port, runtime dependency/version policy, per-process circuit breaker and Backoffice lifecycle, diagnostic invocation and activity views are present. The standard Compose profile mounts the Docker socket only into `extension-controller`; the extension overlay no longer mounts it into the REST API. The runnable reference-container end-to-end test verifies install, invoke, update, rollback and restored invocation. Threat boundaries and residual risks are documented in `docs/extensions/threat-model.md`. Cross-instance circuit-breaker state remains a documented limitation.

Stripe checkout now compensates definitive client rejections transactionally by cancelling the pending order, failing pending payment rows and releasing active stock reservations. Timeouts, conflicts and rate limits remain pending because the provider outcome may be ambiguous and a retry must preserve Stripe's stable order idempotency key. Focused compensation unit tests and a PostgreSQL compensation integration test pass.

V19 adds a durable order-email outbox written with checkout. The scheduled delivery worker uses `SKIP LOCKED`, five-minute claim recovery and bounded exponential retries, keeps recipient email out of the outbox, and redacts message content after delivery or terminal failure. Email delivery is disabled unless explicitly enabled. Testcontainers verified delivery/PII redaction and that checkout idempotency creates only one email event.

## 1. Repository identity and baseline

**Current-scope update (2026-10-13):** Mobile, Kubernetes/Istio and Terraform were removed from the repository after source/reference and uncommitted-change review. No active build or product dependency was found. Reviewed Kubernetes changes were deployment-specific; generated mobile dependencies/build output were removed. Docker Compose remains the documented deployment path. The audit below records the original repository inventory; its historical baseline results are retained as evidence, not current status.

**Latest executable evidence:** `Test-Path artio-mobile`, `Test-Path artio-kubernetes`, and `Test-Path terraform` each returned `False`. `gradlew.bat projects` lists 21 active module projects and none of the retired modules. `docker info` returned Docker Engine 28.5.2. `gradlew.bat clean build integrationTest jacocoTestReport` exited 0 (`BUILD SUCCESSFUL`, 171 tasks); all 10 PostgreSQL-backed integration tests passed, both Angular production builds passed, and Java unit tests passed. `docker compose -f artio-docker/docker-compose.yml config --quiet` and `git diff --check` exited 0. Commit `6074286` is pushed to public `dvoracek-martin/artio:main`; GitHub's combined status response contains no checks, so hosted CI is not verified. `.github/workflows/build.yml` requires Docker and runs the same Gradle quality tasks before building images.

**P1-07 configuration hardening (local, not yet pushed):** `:artio-rest-api:test :artio-rest-api:integrationTest` exited 0 (7 API unit tests, 10 PostgreSQL integration tests). `docker compose --env-file .env.example -f artio-docker/docker-compose.yml config --quiet` exited 0; the same config without an `.env` exited 1 because required credentials were missing. `git diff --check` exited 0. API/Compose no longer contain default database passwords or cryptographic key values; API rejects sample JWT/encryption keys.

> **Historical snapshot below:** Sections 1 onward preserve the original audit baseline for traceability. Statements there about mobile/Kubernetes/Terraform being present, skipped integration tests, unversioned APIs, or missing module checks describe the pre-cleanup/pre-Phase-1 state and are superseded by the current-scope evidence above and the canonical ledger in `ROADMAP.md`.

- Current branch: `master`.
- Recent history: only commit visible in the local checkout is `0148784 Build Artio commerce platform foundation`.
- `git remote -v`: no remotes configured. There is no evidence in this checkout that it is connected to GitHub.
- The worktree is not clean: previous iteration changes remain uncommitted across backend, frontends, build configuration, migrations and infrastructure. Those changes were retained and inspected; this audit did not reset or rewrite them.
- No `AGENTS.md` was found under the checkout. Product/architecture rules therefore come from the supplied directive and repository evidence.
- Build: Gradle wrapper 8.10.2, Kotlin DSL, Java toolchain/release 21, Spring Boot plugin 3.3.5, Maven Central only in Gradle, npm lockfiles for both Angular apps.
- CI: `.github/workflows/build.yml` runs a clean Gradle build, tests/coverage and container builds on GitHub Actions. Docker image publishing is conditional on registry secrets.

### Baseline commands

| Command | Result | Evidence/limits |
|---|---|---|
| `gradlew.bat build` | Exit 0, `BUILD SUCCESSFUL` | Run on 2026-10-13. Backend, Angular and mobile Gradle tasks completed; mostly up-to-date. |
| Earlier `gradlew.bat clean build` | Exit 0, `BUILD SUCCESSFUL` | Clean build completed immediately before this audit. TOTP and AES-GCM tests passed; Angular storefront production build and Expo typecheck passed. PostgreSQL integration tests were reported skipped. |
| `docker compose -f artio-docker/docker-compose.yml config --quiet` | Exit 0 | Compose syntax/configuration parses; services were not started. |
| `kubectl kustomize artio-kubernetes` | Exit 0 | Base manifests render. |
| `kubectl kustomize artio-kubernetes/istio` | Exit 0 after adding its `kustomization.yaml` | Istio sample manifests render. No cluster apply was performed. |
| Docker daemon probe | unavailable | Cannot run Testcontainers, database migration smoke tests, or container runtime checks in this environment. |
| Terraform CLI probe | unavailable | `fmt`/`validate` not run. |
| `git diff --check` | Exit 0 | Whitespace check passed; Git emitted only line-ending conversion warnings. |

The build result is not evidence of successful PostgreSQL migrations, live provider integration, security certification, or production readiness. The integration-test task was skipped because its Docker dependency was unavailable. After this baseline, task P1-01 added the dependency-boundary gate: its positive run passed, its temporary Spring dependency negative probe failed with the expected diagnostic, and `gradlew.bat build` passed with the new gate included. A clean-install migration assertion was added and compiled; execution is still skipped without Docker.

## 2. Verified current architecture and data flow

At the time of the original audit snapshot, the repository was a Gradle modular monolith with separate domain and adapter projects, one Spring Boot REST application, Angular backoffice/storefront, an Expo mobile client, and Compose/Kubernetes/Terraform/observability assets. The mobile, Kubernetes and Terraform directories were later removed; see the current-scope update above.

| Responsibility | Current modules/evidence | Audit finding |
|---|---|---|
| Shared kernel | `artio-kernel` | Used as the API of `artio.domain-module`; domain code is largely framework independent. |
| Catalog | `artio-catalog`, `artio-catalog-postgres`, `artio-catalog-opensearch` | Domain/persistence/search separation exists; localized content and media lifecycle are incomplete. |
| Pricing | `artio-pricing`, `artio-pricing-postgres` | Decimal money, price/discount/tax rules and quote flow exist; regional rule snapshots and locale semantics need expansion. |
| Inventory | `artio-inventory`, `artio-inventory-postgres` | Separate stock and reservation model exists; concurrency, expiry/recovery and checkout behavior need stronger integration tests. |
| Bundles | `artio-bundles`, `artio-bundles-postgres` | Own domain aggregate and configurable choices exist; end-to-end invariants, localized labels and order snapshot completeness need verification. |
| Orders/identity/payments/notifications | Domain modules plus `artio-rest-api` application/controllers | Domain modules exist, but direct Stripe SDK/webhook code and shared REST orchestration are not yet behind Docker plugin contracts. |
| Persistence | Flyway migrations across adapter modules and REST API migrations `V6`–`V10` | Schema ownership is mixed; REST API migration `V10` adds a cross-cutting enterprise schema, without tenant schema. Clean-install PostgreSQL migration test is the supported acceptance; legacy upgrade compatibility is out of scope. |
| Backoffice | `artio-backoffice` Angular 17 | Real catalog, bundle, price, user and shipment workflows exist, but it does not manage plugins, localized content, country rules, license status, or all operational flows. |
| Storefront | `artio-storefront` Angular 17 | A reference shopping journey exists. It must remain optional and consume public APIs; its current importance in Compose/deployment should be treated as packaging policy, not a Core dependency. |
| Optional mobile client | `artio-mobile` Expo/React Native | Basic login/catalog/cart/orders client; not a Core capability and not a substitute for web Backoffice. |

### Current API/integration evidence

The REST application exposes `/api` catalog, variant, category/brand/attribute, pricing/tax/discount, inventory/reservation, bundles, cart/checkout, orders, auth/users, Stripe checkout/webhook/refund, notifications, reports, MFA, consent/privacy, gift card, loyalty, recommendations, fraud assessment, currency, and subscription routes. GraphQL is a read-only catalog query; `/ws` is a private order update channel. OpenAPI generation was added during Phase 1 using springdoc; the runtime JSON and route security assertions have not executed because the Testcontainers integration suite is skipped locally. Routes remain unversioned (`/api`), and route existence alone does not establish all authorization, persistence, idempotency, or failure behavior.

The current checkout path re-quotes price and creates a pending order/reservations before requesting a Stripe hosted checkout session. Stripe provider code lives in Core's `artio-rest-api`; V7 persistence records payments/notifications. Existing Kafka notification handling is not documented as transactionally coupled to state by an outbox. No Docker plugin manifest, registry/image policy, lifecycle manager, Extension API, schema-driven plugin UI, or provider plugin was found.

## 3. Capability matrix

Statuses use the directive's exact vocabulary. `COMPLETE` is reserved for behavior supported by tests and verification; build success alone is insufficient.

| Capability | Status | Evidence and defects/gaps | Recommended action, dependencies, acceptance criteria |
|---|---|---|---|
| Repository/build | PARTIAL | `settings.gradle.kts`, Gradle 8.10.2 wrapper, Java 21 convention plugins and `.github/workflows/build.yml`; local build passes. Working tree is uncommitted; no Git remote; integration gate skipped; no explicit module rule checker. | Retain wrapper/conventions; enforce domain dependency rules, lock/version build inputs, document clean-checkout setup. Pass clean CI build and boundary checks. |
| Catalog | PARTIAL | `artio-catalog`, PostgreSQL/OpenSearch adapters, catalog REST routes and migrations V1–V2; domain/persistence separation. Product content is single-language; media/localized content and full lifecycle are absent or unverified. | Retain domain and adapters; add localized content/fallback and lifecycle invariants. Verify APIs and persistence with migration and application tests. |
| Pricing/tax/promotions | PARTIAL | `artio-pricing`, V3 migration, decimal money and quote endpoints; admin rule endpoints exist. Country model/effective applicability and immutable order tax snapshots are incomplete/unverified. | Retain decimal domain; refactor country/effective-dated rules and snapshots. Test rounding, currencies, validity windows and historical orders. |
| Inventory | PARTIAL | `artio-inventory`, PostgreSQL adapter/migration V4, stock and reservations. Docker-dependent concurrency/expiry/cancel integration evidence is unavailable. | Retain ownership boundary; test oversell, expiry, retry and release with PostgreSQL concurrency tests. |
| Bundles/configurators | PARTIAL | `artio-bundles`, PostgreSQL V5, configuration/quote APIs and storefront flow. Need full backoffice localization, component failure cases and immutable order-time bundle/tax snapshots. | Retain aggregate; extend acceptance tests across pricing, inventory, checkout and order snapshot. |
| Cart/checkout | PARTIAL | REST cart/checkout controllers, price revalidation, reservation and hosted payment hand-off. Idempotent checkout/recovery and failure compensation need tests; guest journey is limited. | Refactor workflow boundaries; test duplicate requests, price/stock changes and partial provider failure. |
| Orders | PARTIAL | Order domain, REST routes, payment/shipping state updates and V8/V9 migrations; WebSocket order updates. Full explicit transition coverage, refunds/returns and historical snapshot immutability are incomplete. | Retain aggregate; enforce/test legal state transitions and idempotency. |
| Identity/access | PARTIAL | BCrypt/JWT, role checks, MFA TOTP/backup hashes, optional Google OIDC. Authorization is role-based with limited resource policy tests; tenant context has been removed to match the single-install target. | Retain core identity/MFA; expand explicit permission and cross-user authorization tests. |
| Payments | CONFLICT | `PaymentController` integrates Stripe directly in Core and accepts Stripe webhooks. Directive requires provider-specific implementation/credentials/webhooks in Docker-only plugins. | Refactor Core to versioned payment contract; move Stripe code to separately built container plugin. Verify signatures, replay/idempotency, refunds and outage behavior. |
| Shipping/fulfillment | PARTIAL | Admin shipment/tracking and order status paths exist. No shipping-rate/label provider contract or Docker carrier plugin. | Retain generic fulfillment state; design Extension API and implement provider through plugin only. |
| Search | PARTIAL | `artio-catalog-opensearch` adapter and product search path exist. Locale-aware indexing, outbox-triggered rebuild, health/recovery/fallback are incomplete. | Retain as derived read model; document ownership and rebuild, add localized index and recovery tests. |
| Notifications/webhooks | PARTIAL | Notification domain, persisted in-app notifications, Stripe webhook receipts with duplicate suppression, and PostgreSQL transactional outbox for order-to-Kafka events. Retry is tested for unavailable Kafka. Email delivery retries/DLQ, webhook receipt retention, consumer-side dedupe and localized templates remain. | Add operator-visible retry diagnostics and retention policy; prove downstream consumers honor stable event IDs. |
| Persistence/migrations | PARTIAL | PostgreSQL schema is defined by one consolidated REST API baseline, assembled from pre-release V1–V27. Decimal/time types and constraints are present; the baseline defines identity/security and commerce tables without tenant keys. Clean install is covered by the migration integration gate. Legacy external upgrade support is out of scope by owner decision. | Use clean-install migration tests; do not add compatibility requirements for nonexistent external databases. |
| Events/messaging | PARTIAL | V18 transactional order-event outbox is persisted with checkout, claims bounded work with `SKIP LOCKED`, retries failed Kafka sends with exponential backoff, and recovers stale claims. V19 persists order-email delivery in the checkout transaction and retries mail with bounded backoff and claim recovery. Testcontainers verifies clean migration, checkout deduplication and email delivery/redaction. Consumers still need schema compatibility and event-ID dedupe; webhook receipt retention and outbox diagnostics remain. | Define consumer compatibility/deduplication, alerting, dead-letter operations and outbox retention before expanding event topics. |
| Plugin platform | MISSING | No plugin manifest/API/runtime/lifecycle/config schema/image identity or Backoffice manager found. Existing Stripe direct code is an architectural conflict. | Implement Phase 2 Docker-only reference plugin proof after Core boundaries and security model. Require manifest validation, lifecycle, secret redaction, health, timeout and no Core rebuild. |
| APIs | PARTIAL | REST controllers, read-only GraphQL and springdoc-generated OpenAPI configuration; source-level generation and security test compile. Runtime contract assertion is not executed without Docker. Routes remain unversioned; consistent error/pagination and Extension API compatibility are absent. | Retain REST baseline; execute generated-contract integration test; introduce `/api/v1` and versioned Extension API with compatibility tests. |
| Backoffice | PARTIAL | Angular 17 app with functioning catalog/bundle/pricing/user/report/shipping screens. No plugin lifecycle/config UI, localization DE/IT/FR/EN, edition/license view or complete loading/error/accessibility evidence. | Retain and elevate as included product; implement workflows incrementally with keyboard, responsive, API authorization and UI tests. |
| Storefront | PARTIAL | Angular 17 reference shop with product/cart/bundle/checkout/order flow and PWA shell. English-first UI; some business assumptions are in the client; no guarantee it is optional in packaging. | Retain as optional API consumer; move authoritative checks to Core and test custom-client independence. |
| Internationalization | PARTIAL | `LocaleService` and consent/navigation labels in EN/CS/DE. Directive requires DE/IT/FR/EN and localized domain content, formatting and fallback; no shared locale model. | Refactor locale codes/fallback and content model before broader content entry. Acceptance: localized catalog round-trip and locale-safe formats/search. |
| Regional commerce rules | PARTIAL | Currency rates and tax-rule endpoints exist; storefront currently defaults market/country to US. No verified Swiss rules, effective-dated regional configuration, invoice/address requirements or order snapshots. | Define configurable country policy and legal verification before defaults; test effective dates, tax snapshots, rounding and address constraints. |
| Operations/deployment | PARTIAL | Compose, Dockerfiles, Kubernetes/Kustomize, Terraform sample, Prometheus/Grafana/Jaeger. Compose parses; Docker unavailable; TF absent; no tested backup/restore, upgrade, image provenance or production secret manager. | Retain local Compose; make supported self-hosted Docker install first-class; test health, secrets, backup/restore and upgrade. Kubernetes stays optional. |
| Security/privacy | PARTIAL | JWT/BCrypt/MFA, AES-GCM MFA secret, audit metadata, CORS/security headers, privacy export/anonymization, consent snapshots. No plugin threat model, broad authorization/security tests, secret rotation, retention/legal assessment or verified audit safety. | Retain useful controls; add threat model and tests for identity, resource auth, redaction, webhooks and plugin isolation. Avoid compliance claims. |
| Testing/quality | PARTIAL | Domain unit tests, TOTP/AES tests, Testcontainers integration source, JaCoCo and CI. Integration tests skipped without Docker; no meaningful E2E, plugin contract, schema migration, concurrency, security or resilience suite. | Add test layers alongside each phase; do not equate coverage/build with behavior. Required DB tests run in CI with Docker. |
| Delivery | PARTIAL | GitHub Actions builds and Docker images; Compose/K8s deployment materials. No release version policy, product license/edition boundary, SBOM/signing/provenance, upgrade guide or restore evidence. | Define release artifacts for Core/plugin images and Community/Enterprise entitlement; validate reproducible install/upgrade/rollback. |

## 4. Retain/refactor/replace/remove classification

| Area | Decision | Rationale and migration protection |
|---|---|---|
| Gradle modular build, Java 21, bounded-context domain modules | **Retain + refactor** | Useful, compiling modular monolith. Add enforceable dependency rules and tighten convention/build reproducibility without broad rewrite. |
| Catalog/pricing/inventory/bundles/orders domain logic | **Retain + refactor** | Existing tested domain behavior is valuable; extend invariants, localization, snapshots and failure tests. Preserve public behavior where compatible. |
| PostgreSQL adapters/Flyway history V1–V9 | **Retain** | Preserve established data and migration sequence; never rewrite a released migration. Test supported clean/upgrade paths. |
| V10 enterprise foundation / tenant architecture | **Refactored; resolved** | Owner confirmed that no external data ever existed. V10 now defines applicable features without SaaS tenant schema; `MigrationIntegrationTests.installsCleanlyThroughCurrentSchemaWithoutTenantModel` and API migration assertions passed against PostgreSQL 16 Testcontainers. Legacy external database compatibility is not required. |
| REST API application | **Retain + refactor** | Good modular-monolith entry point; reduce cross-domain SQL, add OpenAPI/versioning and stable contracts. |
| Stripe provider implementation in API/Core | **Replace in a bounded migration** | Violates Docker-only provider plugin boundary. First introduce versioned Core Extension contract, then move provider implementation and preserve payment records/webhook compatibility. |
| Backoffice | **Retain + extend** | First-class required product; current working workflows are a base, not production-complete. |
| Storefront | **Retain as optional reference** | Useful demonstration; decouple product distribution/Core operation from storefront. |
| GraphQL, WebSockets, mobile, AI/popularity, FX/manual subscription endpoints | **Review/refactor or remove if unsupported** | These arose from older broad brief; directive says avoid unnecessary API styles and placeholders. Keep only where validated by product value and proper tests; do not let them displace required plugin/localization foundations. |
| Kubernetes, Istio samples and Terraform sample | **Retain as optional examples** | Not a local/self-hosting prerequisite; document unverified state and avoid claiming these constitute supported production platform. |
| Kafka | **Required platform infrastructure** | Default Compose starts a persistent KRaft broker and initializes `artio.order.events`; PostgreSQL remains the transactional durability boundary. Complete versioned contracts, consumers, inbox dedupe, retries, DLQ/replay, lag/backlog visibility and failure tests before claiming event-platform completion. |

## 5. Target architecture and dependency rules

1. **Artio Core** is one deployable modular monolith. Domain modules: catalog, pricing/tax, inventory, bundles, cart/checkout, orders, identity/access, payments orchestration, fulfillment, notifications/integration events. Domain modules remain framework/provider independent.
2. Each domain owns application use cases and persistence contracts. PostgreSQL adapters implement those contracts. Controllers expose versioned API DTOs; no cross-domain table writes from a different owner.
3. **Extension API** is a versioned Core-owned contract and plugin management/lifecycle service. Provider-specific code is in independent Docker images. Core calls plugins over a narrow authenticated protocol with deadlines and validated payloads.
4. **Backoffice** is included and manages commerce workflows, permissions, locale/country configuration, edition entitlements and Docker plugin lifecycle/configuration. It calls Core APIs and cannot reveal saved secrets.
5. **Storefront** is a reference API client, optional at install. Mobile remains an optional client only if its maintenance cost is justified.
6. PostgreSQL is transactional truth; OpenSearch is derived/rebuildable. Redis is cache/rate-limit support. Kafka is reserved for documented asynchronous integrations, not routine same-process calls.
7. Each customer deployment is single-installation. No tenant claim, tenant selector or SaaS tenant model in target architecture. Tenant runtime and schema have been removed; installation-level authorization and resource ownership apply.
8. Initial locale set is `de`, `it`, `fr`, `en`; localized content uses stable locale keys and an explicit fallback, with country policy/configuration independent of locale.

Dependency rules: domain modules may depend on `artio-kernel` and explicitly approved domain contracts only; they must not depend on Spring, persistence adapters, API/UI modules, Kafka, or provider SDKs. Persistence adapters depend on their own domain contract. REST application composes domain application interfaces and adapters. Frontends depend only on documented APIs. Plugin images depend on the versioned Extension API, not Core internals.

## 6. Highest risks

1. **Plugin isolation/lifecycle:** no implementation exists, while Stripe is embedded in Core. Must prove one end-to-end Docker plugin before adding providers.
3. **Secrets and trust:** plugin images, configuration, credentials, diagnostics, networks, resource limits and image digest verification have no platform implementation or threat model.
4. **Country/localized data:** current storefront defaults US and current locale service misses Italian/French; tax/address/invoice history lacks demonstrated Swiss-ready configuration and snapshots.
5. **Payments and lifecycle reliability:** direct Stripe API/webhook handling has no demonstrated plugin boundary, broad replay/idempotency evidence, or reconciliation tests.
6. **Persistence and shared SQL:** REST module holds cross-cutting direct JDBC operations; domain/schema ownership is mixed. Testcontainers suite was skipped.
7. **Release/operations:** no verified database backup/restore, migration upgrade, image provenance, security scan, or supported product edition/license path.

## 7. First implementation slice and phase plan

### First slice — `P1-01: Enforce domain dependency boundaries`

**State:** done. **Purpose:** turn the directive's modular-monolith rule into an executable Gradle quality gate before adding more integration architecture.

- Scope: assert domain-module project dependencies target only the kernel or approved domain contracts, and production configurations do not depend on Spring/persistence/provider infrastructure; wire the check into `check` and CI through Gradle.
- Acceptance: valid current graph passes; a deliberate disallowed dependency makes the task fail with actionable module/configuration details; normal `build` invokes the gate.
- Verification: `gradlew.bat verifyModuleBoundaries` passed; a temporary forbidden `org.springframework:spring-context` dependency in `artio-catalog` was rejected with a module/configuration diagnostic and then restored; `gradlew.bat build` passed and ran the check. Configuration cache was stored/reused.
- Migration/rollback: build-only change; no data/API migration. Revert the checker if the real dependency graph reveals a legitimate current edge, then document and approve it rather than broadening allowlists silently.

### Ordered plan

| Phase/task | Scope and acceptance gate | State/dependencies |
|---|---|---|
| P0 — audit/alignment | This report, target architecture, ADRs and capability matrix; no unsupported claim. | Done in this audit; immediately begin P1. |
| P1 — foundations | Enforced module rules, clean build in CI, reproducible config, migration test strategy and docs; no knowingly broken build. | `P1-01` in progress; follow with migration integration tests, OpenAPI and secret/config cleanup. |
| P2 — Core/Extension API + Docker plugin proof | Valid manifest; image identity; compatibility/capability/schema check; Backoffice config/secret redaction; lifecycle; authenticated contract; health/timeouts; isolation/audit/update policy; real integration tests. | Depends on P1 and security threat model. |
| P3 — Catalog | Identity/lifecycle/media and localized content; fallback and API/persistence tests. | Depends on P1; retain/refactor existing catalog. |
| P4 — Price/tax/locale/country | Exact money, effective regional policies, order snapshots, DE/IT/FR/EN formats; verify Swiss defaults from authoritative sources. | Depends on P3. |
| P5 — Inventory | Reservations, expiry/release, checkout integration, concurrency/oversell tests. | Depends on P3/P4. |
| P6 — Bundles | Constraints, localized content, price/tax/stock interaction and immutable order snapshots. | Depends on P3–P5. |
| P7 — Cart/checkout/orders | Idempotent order creation, revalidation, explicit states and compensation/recovery. | Depends on P4–P6. |
| P8 — Payment/shipping plugins | Core contracts and real provider container plugins, sandbox/webhook/idempotency/failure behavior. | Depends on P2/P7. |
| P9 — Search/notifications/integration | Rebuildable locale-aware indexes, justified outbox/events, retries/DLQ/diagnostics. | Depends on P1/P3 and contracts. |
| P10 — Backoffice | Production workflows for domains/plugins/locale/region/license; authorization, accessibility and resilient UI states. | Incremental prerequisites P2–P9. |
| P11 — Storefront reference | Optional documented API client; verified core journeys and localization. | Depends on ready API/domain slices. |
| P12 — operations/release | Reproducible self-hosted install/upgrade, backup/restore, scanning, security/resilience review and release notes. | Depends on all shipped capabilities. |

## 8. Open decisions and explicit constraints

There is no blocking product-owner question for the first implementation slice. The directive already settles single-customer deployment, proprietary Community/Enterprise licensing, Docker-only plugins, included Backoffice, optional reference Storefront, modular monolith, PostgreSQL truth, and DE/IT/FR/EN.

Operational values such as reservation timeouts, retention and payment/shipping providers remain configurable decisions for their dependent phases. The specific provider set and Community/Enterprise feature matrix require a documented recommendation before licensing gates/provider claims ship, but do not block dependency checking or plugin-contract design. V10 provenance is closed – not applicable per owner decision; no external data existed and no legacy schema preservation is required.

## 9. Evidence references

- `settings.gradle.kts`, root `build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`, `buildSrc/src/main/kotlin/*.gradle.kts`
- `artio-*/build.gradle.kts`, `artio-rest-api/src/main/java/io/artio/api/**`
- `artio-*/src/main/resources/db/migration/**`, `artio-rest-api/src/main/resources/db/migration/V6__users_orders.sql` through `V10__enterprise_foundations.sql`
- `artio-docker/docker-compose.yml`, `artio-docker/Dockerfile*`, `.github/workflows/build.yml`
- `artio-storefront`, `artio-backoffice`, `artio-mobile`
- `artio-kubernetes`, `terraform`, `artio-observability`
- Existing technical narrative: `TECHNICAL_STATE.md` (historical implementation status; superseded where it conflicts with the new directive).

## Migration baseline consolidation (2026-10-14)

The pre-release schema migrations V1–V27 have been consolidated, in numeric order, into `artio-rest-api/src/main/resources/db/migration/V1__initial_schema.sql`. The former SQL resources in the PostgreSQL adapter modules and REST API were removed after their complete contents were verified in the baseline. The migration integration assertions now require exactly version 1. This is a clean-install baseline; it is not an upgrade path for a database that has already applied the old sequence. Existing local databases and volumes are retained. A fresh empty database is required to exercise the new baseline.

Safe isolated Compose verification commands and local-database handling are documented in [the Flyway baseline runbook](operations/flyway-baseline.md); no existing local volume needs to be removed to test the new baseline.
