# Product variant administration v1

Products can have multiple sellable variants. Each variant has its own UUID and globally unique SKU, belongs to one product, and has an independently managed active state. The parent product remains the stable catalog identity; inventory and pricing are keyed to the sellable variant UUID where present.

Backoffice Products lists each variant's name, SKU and status. Administrators can add variants and activate/deactivate an existing variant. Creating a product through the existing Backoffice form also creates its `Standard` variant using `<product SKU>-STD`; failure to create that variant is surfaced to the operator because the product itself may already have been created.

## API and authorization

- `GET /api/v1/products/{productId}/variants` lists variants for a product.
- `POST /api/v1/products/{productId}/variants` creates a variant using `{ "name": "...", "sku": "...", "attributes": { "color": "navy", "size": "M" } }`. `attributes` is optional; each key must identify an active catalog attribute definition.
- `GET /api/v1/variants/{variantId}` reads a variant.
- `PUT /api/v1/variants/{variantId}` updates its name, SKU and complete option combination.
- `PUT /api/v1/variants/{variantId}/active` sets its active state.

Read routes remain public with public product data. Variant creation and update are protected by the product administration rule; status changes require ADMIN or SUPERADMIN. SKU uniqueness is enforced by the persisted variant constraint. A non-empty option combination is unique per product, enforced both in the application and with a PostgreSQL JSONB unique index. `TEXT` values are trimmed, `NUMBER` values must parse as decimal numbers and are stored in canonical decimal form (`42.00` becomes `42`), and `BOOLEAN` values accept true/false and are stored lowercase. Attribute codes must be active and option values are bounded. Inactive variants remain addressable for order history but are not offered as active merchandise.

## Verification and remaining scope

The variant combination implementation adds domain events, PostgreSQL persistence, typed validation, duplicate-combination conflict handling, update API and Backoffice create/edit controls. Unit coverage checks normalization, invalid typed values and duplicate rejection. A PostgreSQL integration scenario covers persistence, edits, duplicate rejection and anonymous write denial. The exact command results are recorded in `docs/ROADMAP.md`; Testcontainers runtime acceptance is only claimed when Docker is available. Variant-specific localized content/media and variant merchandising remain future catalog work.
