# Artio editions and signed entitlements v1

Owner decision, 2026-10-15: loyalty, subscriptions and advanced reports require Enterprise. Foundational commerce and plugin management remain Community. Artio is proprietary in both editions.

## Feature boundary

| Capability | Community and after Enterprise expiry | Enterprise entitlement |
|---|---|---|
| Catalog, pricing/tax, inventory, checkout, orders, payments/refunds, shipping, customer identity/privacy, security, media, plugin management and operational diagnostics | Available; no license-server dependency | None |
| Standard reporting | Recent orders, operational status counts, per-currency sales/order/customer totals | None |
| Advanced reporting | Unavailable for new queries | `ADVANCED_REPORTING`: ranked variant performance and per-currency customer lifetime value; extensions to this boundary require an explicit decision |
| Loyalty program | Read/export existing balances and ledger, redeem existing points, reverse/refund existing accrual, and finish accrual already promised on an order | `LOYALTY`: promise points on new orders and create new discretionary program awards/adjustments |
| Subscriptions | Read/export existing contracts, pause/cancel them and satisfy already-existing payment/refund/delivery obligations | `SUBSCRIPTIONS`: create new contracts or reactivate paused/cancelled contracts |

An unavailable Enterprise feature returns HTTP 403 with code `FEATURE_NOT_LICENSED` at the API/use-case boundary. UI hiding is only a convenience. License checks must never throw out of basic payment completion or refund processing. New-order loyalty eligibility must be persisted at order creation and honored on later capture/reconciliation even if the license has expired. Existing pre-boundary orders retain their prior promise; expiry does not erase balances or ledger history. Administrators may inspect existing loyalty obligations without an entitlement. Privacy export/erasure remains Community.

Standard and advanced monetary aggregates must remain separate by currency; adding CHF and EUR is invalid. The current reporting endpoint mixes standard and advanced fields and must be separated while retaining useful Community reports.

## Implemented loyalty and subscription boundaries

V32 adds `orders.loyalty_eligible`. Checkout snapshots the locally available `LOYALTY` grant into this flag when inserting a new order; idempotent replay returns the original order and cannot change its promise. Orders already present before V32 retain eligibility because the previous implementation promised points on every paid order. The new column defaults to false for subsequent orders. There is no API that turns an ineligible order into a promised order retroactively.

Payment/capture/reconciliation, gift-card settlement and the customer earn endpoint honor the stored flag. They do not require a currently active license to fulfill an existing order promise. New Community orders remain payable/refundable and do not accrue points. The customer earn endpoint checks ownership and paid status and returns `FEATURE_NOT_LICENSED` for an order without eligibility. Existing points remain readable and redeemable; refund reversals remain available. New discretionary adjustments require `LOYALTY`, while replay of an already committed matching idempotency key returns its prior result without another award or current-license dependency.

Creating a subscription requires `SUBSCRIPTIONS`. Customer status changes lock the owned contract transactionally. Changing a paused/cancelled contract to active requires the grant; an idempotent active-to-active request does not. Read, pause, cancellation and cancellation replay remain available after expiry, with ownership checks intact. The first due date uses weekly/monthly/quarterly calendar periods in the configured store timezone (the Switzerland default is Europe/Zurich), handling short months and DST. This does not claim an automatic recurring-charge engine or provider sandbox verification.

Real API/PostgreSQL scenarios verify Community checkout/payment/refund, an order promised while licensed and paid after a signed expired grant is installed, points redemption/refund and idempotent replays after expiry, protected new adjustments, subscription creation/reactivation denial and continued read/pause/cancel. Payments in these tests use the real persisted gift-card ledger. A test-only external-provider availability mock permits creation of the pending order and is asserted never to be invoked; no external payment outcome is simulated or claimed. The V30 upgrade test verifies existing orders keep their points promise and new orders default to no promise.

## Local signed contract

Normal requests evaluate a previously verified local document. There is no synchronous network call on an application request. Signing is external: Core and customer installations contain public verification keys only, never an issuer private key.

The JSON envelope has exactly `keyId`, `payload`, and `signature`. `payload` and `signature` are unpadded canonical base64url strings. The signature is Ed25519 over the decoded payload bytes, not a reserialized JSON representation. `keyId` selects a configured trusted Ed25519 X.509 public key. Maximum envelope size is 64 KiB; decoded payload is at most 16 KiB. Duplicate/unknown fields and unknown features are rejected.

The envelope and decoded payload must both be UTF-8. UTF-16/32 auto-detection and malformed UTF-8 are rejected so accepted bytes survive durable TEXT storage unchanged.

The signed payload has exactly these fields:

```json
{
  "version": 1,
  "licenseId": "6ce81dab-9e43-42e3-8a6b-04276f4e2048",
  "installationId": "e0066a59-6d91-4d22-aaf5-73d29b67fbb6",
  "issuedAt": 1792022400,
  "notBefore": 1792022400,
  "expiresAt": 1823558400,
  "graceUntil": 1824163200,
  "features": ["LOYALTY", "SUBSCRIPTIONS", "ADVANCED_REPORTING"]
}
```

IDs are UUIDs. Timestamps are integer Unix seconds, ordered `issuedAt <= notBefore < expiresAt <= graceUntil`; signed grace cannot exceed seven days. Installation ID must match the installation's persisted, backed-up identity. A document issued more than five minutes in the future is invalid. Feature values are unique, explicit and limited to this matrix; an empty feature set is valid and grants no Enterprise access.

The policy is active at `notBefore <= now < expiresAt`, then grace at `expiresAt <= now < graceUntil`, then expired. Grace grants only the signed features, is evaluated against the signed absolute deadline, and cannot be renewed by restart, repeated refresh failure or local receipt time. A future document does not grant access before `notBefore`. Missing/invalid local state defaults to useful Community. Verification never grants access for unknown keys, modified payloads, wrong installation IDs or unsupported schema versions.

## Refresh and failure policy

V31 persists the last verified document and installation UUID atomically in the singleton `artio_license_state` PostgreSQL row, with audit in the same transaction. Verified memory state changes only after the outermost transaction commits. Each process reloads database state at most once per minute, so cross-process replacement/revocation propagation is bounded to one minute; signed expiry is evaluated on every check. Database backup/restore preserves both the entitlement and installation binding. See [ADR-0003](../adr/0003-postgres-entitlement-state.md).

Optional refresh uses a configured HTTPS GET endpoint, a 64 KiB response cap, a five-second deadline including body delivery, no redirects, and a controlled schedule (six hours by default; configurable between 60 and 86400 seconds). The first scheduled attempt occurs after one minute. Only the web API role runs this dedicated scheduler; it does not enable unrelated API workers. Refresh is disabled when no endpoint is configured. An optional refresh credential is read from a restricted file (maximum 4 KiB) and never returned to the browser or included in audit/logs. Endpoint provisioning associates the installation with an issuer-side credential/URI; the runtime does not automatically send customer or installation data to an unconfigured service.

A refresh network/TLS/HTTP/parsing/signature failure retains the last verified document and its original deadlines. A newly verified, currently applicable signed document replaces it; this includes signed removal of feature grants. Future-dated replacements must not prematurely erase a currently applicable document. Expiry ends only new Enterprise activity listed above. Core commerce and fulfillment of existing obligations remain operational.

Within the local last-known-good state, replacement `issuedAt` must increase. Reinstalling an identical grant is idempotent; a different grant with the same issue time or an older grant is rejected to prevent stale refresh/manual responses from undoing a newer signed feature removal. Issuers must advance issue time when changing a grant. Restoring an old backup restores its entitlement history too; this component does not implement an external anti-rollback ledger.

Owner status exposes the installation identity, edition/state, feature availability, validity/grace deadlines and safe refresh state. It does not expose the signed envelope, issuer/license/customer identifiers, refresh token, public-key configuration or private data. Manual install/refresh is restricted to SUPERADMIN and audited without license contents.

## Operator configuration and API

Community starts with no issuer trust file, signed document or refresh endpoint. The database generates its identity automatically. A missing/invalid trust file leaves Community functional and reports a safe configuration state; it never enables Enterprise.

`ARTIO_LICENSE_PUBLIC_KEYS_FILE` points to a read-only JSON object mapping up to eight key IDs to standard-base64 X.509 Ed25519 **public** keys. Duplicate IDs, private keys, other algorithms, unknown key-ID characters, malformed JSON and files larger than 16 KiB are rejected. Obtain this trust file through the issuer's approved distribution channel; do not generate a customer signing key or add a private key to this deployment. Compose passes this path to API/event/notification processes. A path inside `/run/artio-secrets/` uses the existing restricted persistent secret volume and is backed up with it. Restart those processes when changing trust anchors.

`ARTIO_LICENSE_REFRESH_URL` optionally configures the HTTPS issuer endpoint. `ARTIO_LICENSE_REFRESH_CREDENTIAL_FILE` optionally names a protected bearer credential file, usually in the same secret volume. The token is never an environment-variable value. `ARTIO_LICENSE_REFRESH_INTERVAL_SECONDS` defaults to `21600`. These refresh settings apply to the API process only; workers evaluate the shared local state.

All following canonical routes require an authenticated SUPERADMIN (the `/api/...` compatibility alias remains available):

| Method and route | Behavior |
|---|---|
| `GET /api/v1/admin/system/license` | Safe status and persistent installation ID to provide to the issuer |
| `POST /api/v1/admin/system/license` | Raw signed JSON envelope, maximum 64 KiB for fixed/chunked uploads; validate and atomically install with audit |
| `POST /api/v1/admin/system/license/refresh` | Explicit controlled refresh; returns safe status and audits the owner request even if refresh is unconfigured |

The status has `installationId`, `edition`, `state`, available `features`, `expiresAt`, `graceUntil`, `verificationStatus`, `refreshConfigured`, `refreshStatus`, `lastRefreshAttemptAt`, `lastRefreshSuccessAt` and `refreshInProgress`. An empty or expired grant exposes an empty feature set. Refresh states include `NOT_CONFIGURED`, `NEVER`, `IN_PROGRESS`, `SUCCESS`, `UNAVAILABLE`, `INVALID_RESPONSE` and `INVALID_CONFIGURATION`; no issuer response body/error message is exposed. Invalid uploads return 400 and preserve the current document; oversized uploads return 413. Unauthorized callers return 401/403 before upload parsing.

## Implementation evidence

`SignedEntitlementVerifier` and `EntitlementPolicy` implement local Ed25519 verification and validity/replacement policy. Ten tests verify strict UTF-8 persistence compatibility, exact active/grace/expired boundaries, explicit features, installation matching, tampering/unknown issuer rejection, strict schema/duplicate handling, time/size/encoding limits, Community defaults, last-known-good behavior, restart expiry, signed feature removal and stale/future replacement rejection. Ephemeral issuer private keys exist only in tests.

Runtime integration is implemented with PostgreSQL persistence, local cache, owner-only API, bounded HTTPS refresh and a dedicated scheduler. PostgreSQL/real-TLS integration tests cover restart, expiry, successful refresh with a file credential, safe responses, tampering/malformed/oversized/redirect/slow/TLS failure, transactional rollback and stale replacement across processes. Real API tests cover role authorization and fixed/chunked upload limits; migration and backup tests verify persistent identity. Consult the task ledger for the latest executed gates.

Loyalty and subscription use-case boundaries and expiry obligation tests are implemented. Remaining licensing completion gates: currency-separated standard/advanced reports with `ADVANCED_REPORTING` enforcement, and Backoffice edition/status behavior. The licensing phase is not complete until those pass too.
