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

## Local signed contract

Normal requests evaluate a previously verified local document. There is no synchronous network call on an application request. Signing is external: Core and customer installations contain public verification keys only, never an issuer private key.

The JSON envelope has exactly `keyId`, `payload`, and `signature`. `payload` and `signature` are unpadded canonical base64url strings. The signature is Ed25519 over the decoded payload bytes, not a reserialized JSON representation. `keyId` selects a configured trusted Ed25519 X.509 public key. Maximum envelope size is 64 KiB; decoded payload is at most 16 KiB. Duplicate/unknown fields and unknown features are rejected.

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

The runtime integration must persist the last verified document atomically in a dedicated writable license volume, separate from read-only installation secrets. Optional refresh uses a configured HTTPS endpoint, bounded response size/deadline, no redirects, and a controlled schedule (six hours by default). Refresh is disabled when no endpoint is configured. An optional refresh credential is read from a restricted file and never returned to the browser or included in audit/logs.

A refresh network/TLS/HTTP/parsing/signature failure retains the last verified document and its original deadlines. A newly verified, currently applicable signed document replaces it; this includes signed removal of feature grants. Future-dated replacements must not prematurely erase a currently applicable document. Expiry ends only new Enterprise activity listed above. Core commerce and fulfillment of existing obligations remain operational.

Within the local last-known-good state, replacement `issuedAt` must increase. Reinstalling an identical grant is idempotent; a different grant with the same issue time or an older grant is rejected to prevent stale refresh/manual responses from undoing a newer signed feature removal. Issuers must advance issue time when changing a grant. Restoring an old backup restores its entitlement history too; this component does not implement an external anti-rollback ledger.

Owner status exposes the installation identity, edition/state, feature availability, validity/grace deadlines and safe refresh state. It does not expose the signed envelope, issuer/license/customer identifiers, refresh token, public-key configuration or private data. Manual install/refresh is restricted to SUPERADMIN and audited without license contents.

## Implementation evidence

`SignedEntitlementVerifier` and `EntitlementPolicy` implement local Ed25519 verification and validity/replacement policy. Nine tests verify exact active/grace/expired boundaries, explicit features, installation matching, tampering/unknown issuer rejection, strict schema/duplicate handling, time/size/encoding limits, Community defaults, last-known-good behavior, restart expiry, signed feature removal and stale/future replacement rejection. Ephemeral issuer private keys exist only in tests.

Remaining completion gates: runtime storage/HTTPS refresh tests, server boundary tests, payment/loyalty expiry obligation tests, subscription cancellation/reactivation tests, currency-separated reporting tests, and Backoffice edition/status behavior. Do not infer that the entire licensing phase is complete from verifier-only coverage.
