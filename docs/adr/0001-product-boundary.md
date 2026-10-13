# ADR-0001: Self-hosted single-install product and edition boundary

- **Status:** Accepted — mandated by `ARTIO-Codex-Final-Implementation-Directive-v3.1.md` v3.1.
- **Date:** 2026-10-13.
- **Owner:** Product owner decision supplied in the directive.

## Context

The prior implementation iteration added a SaaS-style tenant registry, tenant membership, tenant JWT claim and request header selection in uncommitted code and Flyway V10. The current repository audit shows these in `TenantContext`, `TenantContextFilter`, `TenantController`, authentication/OIDC flows and `V10__enterprise_foundations.sql`. The binding product brief states that Artio is independently deployed and customer-controlled; SaaS multitenancy is explicitly out of scope. The brief also defines the product as proprietary with a useful free Community edition and licensed Enterprise capabilities.

## Decision

Artio has one merchant installation/database per customer. Tenant resolution, tenant-switch headers, membership registries and SaaS isolation are not part of the target architecture. Enterprise feature access is enforced by a dedicated local signed-entitlement component at API/application boundaries. Normal commerce does not synchronously depend on a licensing server. Community remains useful for real commerce.

## Alternatives considered

1. Preserve multi-tenancy as optional: rejected because it creates a permanent second deployment/security model contrary to the product decision.
2. Remove existing tenant schema immediately: rejected until deployment provenance for V10 is known; an additive data-preserving migration is required if it has been applied anywhere.
3. Preserve single-store tenant as internal framework: rejected unless a later concrete need demonstrates value; it obscures ownership and keeps SaaS concepts in security/data paths.

## Consequences

- Code that selects/validates tenants and data access scoped by tenant must be refactored to installation-level authorization and ordinary resource ownership.
- The uncommitted V10 schema cannot be treated as deployed or disposable without evidence. Verify all environments before changing the migration history; if already applied, add a safe forward migration.
- API/JWT compatibility is not promised for these uncommitted development claims; deployed customer data/API behavior must be preserved based on actual release evidence.
- License/edition policy must be isolated and tested, with public-key-only verification in customer images and a defined refresh/grace policy.

## Evidence and follow-up

- Product decision: directive sections 2.1, 2.2 and 20.4.
- Existing conflict: `artio-rest-api/src/main/resources/db/migration/V10__enterprise_foundations.sql`; `TenantContextFilter.java`; `AuthController.java`; `TenantController.java`.
- Follow-up task `P1-02` records the migration-provenance blocker and data-safe correction criteria in `docs/ROADMAP.md`.

## Decision register fields

- **Question:** Should this installation support more than one merchant tenant? **Decision:** No.
- **Options:** SaaS tenants / one installation per merchant. **Recommended:** one installation, selected by product owner.
- **Trade-off:** removes SaaS resource isolation needs and simplifies the operator model, but requires replacing tenant-scoped current APIs/data access.
- **Blocks:** migration/schema removal only until V10 deployment provenance is known; does not block independent Phase 1/2 work.
