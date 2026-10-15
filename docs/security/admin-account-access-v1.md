# Administrator-managed account access

`PUT /api/v1/admin/users/{id}/access` assigns a user's role and, for an `ADMIN`, the selected global permissions in one database transaction. The caller must be a currently authorized `SUPERADMIN`; the usual persisted-role and active-account checks apply on every request.

```json
{
  "role": "ADMIN",
  "permissions": ["INVENTORY_MANAGE", "ORDER_FULFILL"]
}
```

Supported roles are `CUSTOMER`, `ADMIN`, and `SUPERADMIN`. Permission names must match the `Permission` enum. Invalid roles or permissions return `400` without changing the account. An `ADMIN` receives only the submitted permissions, an empty set means no permissions, a `CUSTOMER` is stored with `NONE`, and a `SUPERADMIN` receives full access. A superadministrator cannot change their own role; the endpoint returns `409` and leaves the account unchanged.

The Backoffice access editor submits role and permissions together. It prevents duplicate saves, keeps the form open after an error, and announces pending and failure states in the selected EN/DE/IT/FR locale. The older role and permissions endpoints remain available for API compatibility.
