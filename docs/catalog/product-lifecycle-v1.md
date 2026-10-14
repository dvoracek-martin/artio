# Product duplication and lifecycle v1

`POST /api/v1/products/{id}/duplicate` is restricted to ADMIN/SUPERADMIN by the catalog write policy. The request supplies the copy's new `name`, `description`, and globally unique `sku`. Artio creates a new product ID and an **inactive draft**, carries over the source brand, categories, and currently assigned attribute values, persists it, and refreshes the configured search index under the catalog write lock. The SKU is validated by the domain and database uniqueness constraint. The source product is not modified.

Backoffice Products provides the Duplicate action and asks for a new name and SKU. The product must be explicitly activated before appearing as published catalog content.

Products can be soft-archived with `PUT /api/v1/products/{id}/archive` and restored using `PUT /api/v1/products/{id}/restore`. V30 stores `archived_at`; archive marks the product inactive and retains its stable product ID, SKU, variants, translations and historical order references. Hard deletion is not exposed. A restored product returns as an inactive draft and must be explicitly activated again. Archived products cannot receive new variants or activate existing variants. Backoffice keeps a separate Archived filter and Restore action.

The duplicate operation does not clone variant records, localized translation rows, media assets, scheduled publication data, or merchandising associations. Those are separate product-lifecycle workflows and remain unfinished; the duplicate API does not imply they were copied.

Archive verification: catalog domain tests, selected API tests, integration source compilation and Backoffice production build passed. The targeted V30 clean-install and archive/restore PostgreSQL integration methods were requested and skipped because Docker was unavailable; they have not been runtime-verified. See the 2026-10-14 technical state ledger.
