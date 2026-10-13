# ADR-0001: Self-hosted single-install product and edition boundary

- **Status:** Accepted — mandated by `ARTIO-Codex-Final-Implementation-Directive-v3.1.md` v3.1.
- **Date:** 2026-10-13.
- **Owner:** Product owner decision supplied in the directive.

## Context

The prior implementation iteration added SaaS tenancy. The binding product brief states that Artio is independently deployed and customer-controlled; SaaS multitenancy is explicitly out of scope. The project owner has confirmed that no production or other external data ever existed, so V10 was aligned directly to the single-installation schema. The brief also defines the product as proprietary with a useful free Community edition and licensed Enterprise capabilities.

## Decision

Artio has one merchant installation/database per customer. Tenant resolution, tenant-switch headers, membership registries and SaaS isolation are not part of the target architecture. Enterprise feature access is enforced by a dedicated local signed-entitlement component at API/application boundaries. Normal commerce does not synchronously depend on a licensing server. Community remains useful for real commerce.

## Alternatives considered

1. Preserve multi-tenancy as optional: rejected because it creates a permanent second deployment/security model contrary to the product decision.
2. Remove the tenant schema and runtime tenancy: accepted. No legacy external database compatibility is required.
3. Preserve single-store tenant as internal framework: rejected unless a later concrete need demonstrates value; it obscures ownership and keeps SaaS concepts in security/data paths.

## Consequences

- Former code that selected tenants and scoped data by tenant was refactored to installation-level authorization and ordinary resource ownership.
- The project owner confirmed that no production or external data ever existed. V10 may be shaped directly for the target schema; old database preservation is out of scope.
- API/JWT compatibility is not promised for these uncommitted development claims; legacy external database behavior is not a compatibility target.
- License/edition policy must be isolated and tested, with public-key-only verification in customer images and a defined refresh/grace policy.

## Evidence and follow-up

- Product decision: directive sections 2.1, 2.2 and 20.4.
- V10 currently records the single-installation schema. The former tenant context, tenant controller, and tenant JWT handling have been removed.
- `P1-02` is closed – not applicable; `docs/ROADMAP.md` records the owner decision and clean-install acceptance.

## Decision register fields

- **Question:** Should this installation support more than one merchant tenant? **Decision:** No.
- **Options:** SaaS tenants / one installation per merchant. **Recommended:** one installation, selected by product owner.
- **Trade-off:** removes SaaS resource isolation needs and simplifies the operator model, but required replacing the former tenant-scoped APIs/data access.
- **Status:** the former V10 provenance question is closed – not applicable by owner decision. Clean installation is tested; no legacy database migration is required.
