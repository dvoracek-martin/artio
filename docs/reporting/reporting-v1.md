# Operational reports v1

All routes require ADMIN or SUPERADMIN. Canonical paths use `/api/v1`; the `/api` compatibility alias remains supported. Responses are read-only and assembled under a PostgreSQL repeatable-read transaction. No reporting request calls a license server or converts currencies implicitly.

| Route | Edition | Scope |
|---|---|---|
| `GET /api/v1/admin/reports?days=30` | Community | Order activity and captured/refunded tender, grouped by currency |
| `GET /api/v1/admin/reports/advanced?days=30` | `ADVANCED_REPORTING` | Variant ranking and currency-separated customer lifetime captured value |
| `GET /api/v1/admin/orders/recent` | Community | Existing most-recent 100-order view; unchanged |

`days` defaults to 30 and accepts integers from 1 through 365. The order-creation window is `since <= created_at < until`, using rolling 24-hour days. Invalid period values, including nonnumeric values, return the common 400 error envelope. Anonymous callers receive 401; customers receive 403. An administrator without the advanced grant receives 403 `FEATURE_NOT_LICENSED` before financial aggregation runs. A different Enterprise grant does not grant access. Expiry blocks new advanced queries and leaves standard reports available.

The Backoffice Reports page uses bounded 7, 30, 90 or 365 day periods, displays standard order activity and currency-separated tender, and loads advanced sections only when the safe staff feature-availability endpoint includes `ADVANCED_REPORTING`. Each amount is formatted with its own ISO currency. The UI keeps the stored `recordedLineValue` label and explains its stored-price basis; it does not present it as net captured revenue.

## Standard response

`since`, `until`, `orders`, `activeCustomers`, `ordersByStatus` and `salesByCurrency` describe the same creation cohort. Order/customer/status counts include unpaid and cancelled activity. Each currency row contains `currency`, `orders`, `customers`, `capturedAmount`, `refundedAmount` and `netCapturedAmount`.

Tender totals use persisted payments in `CAPTURED`, `PARTIALLY_REFUNDED` or `REFUNDED` state and the corresponding confirmed `refunded_amount` projection. Pending, failed, authorized and cancelled payments contribute no captured value. Payments are aggregated per order before joining activity, preventing multiple tenders from duplicating order counts. Partial captured tender on an unpaid order contributes its actual captured amount. Full refunds produce zero net value rather than removing the customer or original captured amount.

The window selects orders, not payment settlement dates. Figures describe current recorded tender/refund state of those orders at the read snapshot. They are operational captured-tender metrics, not a settlement-date cashflow, invoice or general-ledger report. There is no currency-free monetary total.

The pre-release `/reports` response previously mixed currencies in `revenue` and lifetime values and included advanced fields in the standard response. Those unsafe/paid fields are replaced by explicit currency rows and the separately gated advanced endpoint. Repository Backoffice/Storefront code had no consumer of the old report shape. Clients must consume `salesByCurrency`; no fake zero advanced values or implicit FX fallback is returned.

## Advanced response

`topVariants` ranks the original quantities on fully captured positive-value orders in the selected creation cohort. Captured amounts must cover the order total. Partial/unpaid orders are excluded. Later refunds do not rewrite original purchased quantities; this metric does not infer returned units from a monetary refund. Ranking is deterministic, with at most ten entries **per currency**, ordered by units then stable variant ID.

Each row includes `currency`, `variantId`, nullable current catalog `sku`, `variantName`, `productId`, `productName`, `unitsOnCapturedOrders`, `recordedLineValue` and `rank`. Missing historical catalog labels do not remove the order line. Recorded line value is the sum of stored order-item unit price times quantity; it is not net captured tender or an allocated refund/tax/shipping metric. Discounts, bundles and inclusive/exclusive prices can make recorded line value differ from final tender. It must not be labelled net revenue.

`customerLifetimeValues` uses all orders created before `until`, independently of the ranking's `since`. It groups successful captured/refunded payment state by customer and currency. Fully refunded customers remain in the denominator; unpaid-only customers do not. Fields are `currency`, `customers`, `capturedAmount`, `refundedAmount`, `netCapturedAmount` and `averageNetCapturedValue`. The average rounds HALF_EVEN to the currency's ISO minor-unit precision. Totals preserve stored decimal values. No customer identity or per-customer financial data is returned.

## Verification and related repairs

`ReportingServiceTests` proves bounded-period rejection and unavailable-feature denial before financial database access. Real HTTP/PostgreSQL `ReportingApiIntegrationTests` cover independent edition grants and expiry, role authorization, multiple tenders, refunds/zero-valued customers, partial/unpaid/cancelled orders, old/future creation times, currency-separated ranking and lifetime values, catalog labels and per-currency ranking limits.

The same database gate repairs and verifies direct `Instant` JDBC binding in report queries, manual currency-rate writes and fraud-assessment time predicates. FX conversion now uses the target currency's ISO precision, including zero-digit JPY and three-digit KWD, rather than assuming two decimal places. Rates remain explicitly merchant-configured; no live-rate service or regulatory fraud-model claim is introduced.
