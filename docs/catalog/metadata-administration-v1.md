# Catalog metadata administration v1

Backoffice → Catalog data manages three existing catalog metadata models through the versioned API:

- **Categories:** create root or child categories, change name or parent, and activate/deactivate. The API rejects parent cycles and missing parents. Deactivation is used instead of deleting records already referenced by products.
- **Brands:** create, edit name/description, and activate/deactivate.
- **Attribute definitions:** create a unique code, label, `TEXT`/`NUMBER`/`BOOLEAN` type and required flag; rename or deactivate existing definitions. Type and requiredness are immutable after creation so stored product values are not silently reinterpreted.

Mutation endpoints require ADMIN or SUPERADMIN credentials. Public catalog reads continue to use the same read endpoints. Product assignment of a brand/category/attribute value is available through `PUT /api/v1/products/{id}/catalog-metadata`; the Backoffice product-assignment editor is still separate follow-up work.
