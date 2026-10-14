# Loyalty v1

## Customer API

- `GET /api/v1/loyalty/me` returns the current balance and tier.
- Confirmed payment accrues whole points once per order. `POST /api/v1/loyalty/earn/{orderId}` remains idempotent for existing clients; it cannot accrue a second `EARN` ledger entry. For legacy paid orders without an accrual entry, it calculates points against the remaining net paid amount.
- `POST /api/v1/loyalty/redeem` requires an `Idempotency-Key` and a positive amount in increments of 100. A repeated key and amount returns the current account; reusing it for a different amount returns `409`. Redemption requires sufficient balance.

## Refund reversals

Each confirmed payment refund is linked to a `REFUND` ledger transaction by the stable refund UUID. Cumulative reversed points are `floor(earned order points × cumulative order refunded amount / order total)`, capped at points earned. The delta is applied for each refund, so repeated and partial refunds do not reverse points twice. If the customer has already spent the points, the balance may become negative; future accruals first pay down that points debt. A full refund reverses all points originally earned for the order.

## Staff administration

ADMIN/SUPERADMIN can read the balance and last 200 ledger transactions at `GET /api/v1/admin/loyalty/{userId}` and adjust at `POST /api/v1/admin/loyalty/{userId}/adjust`. Adjustments require a signed non-zero integer within ±1,000,000, a reason up to 500 characters, and an `Idempotency-Key`. The immutable transaction ledger retains the reason and operator audit entry. The Backoffice Customers view exposes account inspection and adjustment.

## Current earning rule

The existing product rule grants one point per whole unit of the paid order's recorded total and floors fractional units. A market-specific points conversion rate is not configured yet; merchants should use one reporting currency until that policy is defined for additional regions.
