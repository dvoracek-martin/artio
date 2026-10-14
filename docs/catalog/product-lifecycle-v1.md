# Product duplication and lifecycle v1

`POST /api/v1/products/{id}/duplicate` is restricted to ADMIN/SUPERADMIN by the catalog write policy. The request supplies the copy's new `name`, `description`, and globally unique `sku`. Artio creates a new product ID and an **inactive draft**, carries over the source brand, categories, and currently assigned attribute values, persists it, and refreshes the configured search index under the catalog write lock. The SKU is validated by the domain and database uniqueness constraint. The source product is not modified.

Backoffice Products provides the Duplicate action and asks for a new name and SKU. The product must be explicitly activated before appearing as published catalog content.

The current operation does not clone variant records, localized translation rows, media assets, scheduled publication data, or merchandising associations. Those are separate product-lifecycle workflows and remain unfinished; the duplicate API does not imply they were copied.
