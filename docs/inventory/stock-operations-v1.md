# Stock operations v1

Inventory is maintained per sellable (product variant) and warehouse. Staff can create a warehouse, receive stock, set an absolute on-hand quantity with a required reason, and inspect the current available, reserved and on-hand balances from Backoffice → Inventory. Reservation invariants remain domain-owned: a correction cannot set on-hand below reserved quantity, and reservations lock the stock row before checking availability.

## Stock movement ledger

Every persisted stock-domain transition appends an `inventory_stock_movement` row in the same PostgreSQL transaction as the stock level update:

| Type | On-hand delta | Reserved delta |
|---|---:|---:|
| `RECEIPT` | received quantity | 0 |
| `ADJUSTMENT` | new absolute on-hand minus previous on-hand | 0 |
| `RESERVATION` | 0 | reserved quantity |
| `RELEASE` | 0 | negative released quantity |
| `ALLOCATION` | negative allocated quantity | negative allocated quantity |

Rows snapshot resulting balances, actor ID for staff HTTP receipts/corrections, optional staff reason, and time. History is append-only. System-driven movements may have no staff actor. `GET /api/v1/warehouses/{warehouseId}/stock/{sellableId}/movements?limit=100` returns at most 200 newest entries.

The receipt endpoint accepts `{ "quantity": 4, "reason": "Supplier delivery 451" }`. Absolute adjustment uses `PUT /api/v1/warehouses/{warehouseId}/stock/{sellableId}` with `{ "quantity": 12, "reason": "Cycle count 2026-10-14" }`; the reason is required and bounded to 500 characters. Quantities retain the inventory domain's four-decimal precision and reservation checks.

Low-stock thresholds, alerts and cross-warehouse inventory reports are not part of this slice and remain follow-up work.
