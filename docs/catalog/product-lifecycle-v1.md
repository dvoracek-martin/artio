# Product duplication and lifecycle v1

`POST /api/v1/products/{id}/duplicate` is restricted to ADMIN/SUPERADMIN by the catalog write policy. The request supplies the copy's new `name`, `description`, and globally unique `sku`. Artio creates a new product ID and an **inactive draft**, carries over the source brand, categories, currently assigned attribute values, localized names/descriptions and sanitized media blobs/metadata, persists it, and refreshes the configured search index under the catalog write lock. Copied localized content has no SEO slug, avoiding collisions with the source's locale-scoped unique canonical URL; staff can assign a new slug to the draft. The SKU is validated by the domain and database uniqueness constraint. The source product is not modified.

Backoffice Products provides the Duplicate action and asks for a new name and SKU. The product must be explicitly activated before appearing as published catalog content.

Products can be soft-archived with `PUT /api/v1/products/{id}/archive` and restored using `PUT /api/v1/products/{id}/restore`. The current development schema stores `archived_at`; archive marks the product inactive and retains its stable product ID, SKU, variants, translations and historical order references. Hard deletion is not exposed. A restored product returns as an inactive draft and must be explicitly activated again. Archived products cannot receive new variants or activate existing variants. Backoffice keeps a separate Archived filter and Restore action.

The duplicate operation does not clone variant records, scheduled publication data, or merchandising associations. These remain separate product-lifecycle workflows and are not implied by duplication.

Archive verification: catalog domain tests, selected API tests, integration source compilation and Backoffice production build passed. The current single-snapshot clean install is covered by the API integration test; archive/restore PostgreSQL behavior is exercised by its dedicated API integration case.

Duplication copy acceptance: `ProductMediaServiceTests` passed and the updated duplicate API integration scenario compiles. Its PostgreSQL/blob-storage execution has not been run because Docker Engine is unavailable.
