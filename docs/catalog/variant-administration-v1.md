# Product variant administration v1

Products can have multiple sellable variants. Each variant has its own UUID and globally unique SKU, belongs to one product, and has an independently managed active state. The parent product remains the stable catalog identity; inventory and pricing are keyed to the sellable variant UUID where present.

Backoffice Products lists each variant's name, SKU and status. Administrators can add variants and activate/deactivate an existing variant. Creating a product through the existing Backoffice form also creates its `Standard` variant using `<product SKU>-STD`; failure to create that variant is surfaced to the operator because the product itself may already have been created.

## API and authorization

- `GET /api/v1/products/{productId}/variants` lists variants for a product.
- `POST /api/v1/products/{productId}/variants` creates a variant using `{ "name": "...", "sku": "..." }`.
- `GET /api/v1/variants/{variantId}` reads a variant.
- `PUT /api/v1/variants/{variantId}/active` sets its active state.

Read routes remain public with public product data. Variant creation is protected by the product administration rule; status changes require ADMIN or SUPERADMIN. SKU uniqueness is enforced by the persisted variant constraint. Inactive variants remain addressable for order history but are not offered as active merchandise.

## Verification and remaining scope

`gradlew.bat :artio-rest-api:compileIntegrationTestJava :artio-backoffice:npmBuild --no-daemon` exited 0 after adding the Backoffice variant controls and an integration assertion that anonymous status changes return `401`. The integration scenario compiles but was not run against PostgreSQL because Docker is unavailable. `git diff --check` passed. Attribute-based variant combinations, variant-specific localized content/media and variant merchandising remain future catalog work.
