# Catalog metadata administration v1

Backoffice → Catalog data manages three existing catalog metadata models through the versioned API:

- **Categories:** create root or child categories, change name or parent, and activate/deactivate. The API rejects parent cycles and missing parents. Deactivation is used instead of deleting records already referenced by products.
- **Brands:** create, edit name/description, and activate/deactivate.
- **Attribute definitions:** create a unique code, label, `TEXT`/`NUMBER`/`BOOLEAN` type and required flag; rename or deactivate existing definitions. Type and requiredness are immutable after creation so stored product values are not silently reinterpreted.

Mutation endpoints require ADMIN or SUPERADMIN credentials. Public catalog reads continue to use the same read endpoints. Product assignment of a brand/category/attribute value is available through `PUT /api/v1/products/{id}/catalog-metadata`; Backoffice provides a product selector and editor for the brand, categories, and typed attribute values.

The editor retains currently assigned inactive category and attribute data on save. Inactive categories and definitions cannot be newly assigned; existing inactive attribute values are read-only until the definition is reactivated. Clearing an inactive stored attribute is rejected by the API, preventing an unrelated metadata edit from silently destroying a value. Products without catalog metadata are loaded from the public product list and can be classified from this screen.

Verification (2026-10-14): `gradlew.bat :artio-rest-api:test --tests io.artio.api.catalog.ExtensionProductSearchAdapterTests :artio-rest-api:compileIntegrationTestJava :artio-backoffice:npmBuild --no-daemon` exited 0; 2 focused search-adapter unit tests passed, integration test sources compiled, and the Backoffice Angular production build passed. `git diff --check` exited 0. Docker/PostgreSQL-backed metadata assignment scenarios compile but were not executed in this run.
