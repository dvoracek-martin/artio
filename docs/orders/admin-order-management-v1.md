# Backoffice order management API v1

The Backoffice order workflow uses the authenticated Core API. The server enforces ADMIN or SUPERADMIN authorization; customer roles cannot use these routes. `/api/v1` is the canonical versioned path.

## List and search

`GET /api/v1/admin/orders?page=0&size=25&status=PAID&q=buyer%40example.com&sort=createdAt&direction=desc`

The response contains `items`, `page`, `size`, `totalElements`, and `totalPages`. Page numbering starts at zero, page size is bounded to 100, and the supported status values are `PENDING`, `CONFIRMED`, `PAID`, `SHIPPED`, `DELIVERED`, `CANCELLED`, and `RETURNED`. Search matches order ID, customer email, or tracking number. Supported sort fields are `createdAt`, `total`, and `status`; direction is `asc` or `desc`. The UUID is a stable tie-breaker.

## Order detail

`GET /api/v1/admin/orders/{orderId}` returns customer identity, current order state, shipping and billing address snapshots, tax snapshot, line items, and payment summaries. Provider references are visible to authorized staff for support and reconciliation. A missing order returns the standard versioned API error envelope with HTTP 404.

## CSV export

`GET /api/v1/admin/orders/export.csv` accepts the same `status` and `q` filters. Export is bounded to 100 orders; larger result sets return HTTP 400 and require narrower filters. CSV cells are quoted and formula-leading values are neutralized. Responses use `Cache-Control: no-store` and an attachment filename.

The Backoffice exposes search, status filtering, paging, detail review, shipping actions, and CSV export. The current order detail presents accepted totals, customer/address data, lines and payment status. Refund and return workflows remain separate roadmap work and are not implied by these read endpoints.
