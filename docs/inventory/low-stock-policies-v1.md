# Low stock policies v1

An administrator can configure one reorder threshold for each warehouse and sellable product/variant. The threshold applies to **available** quantity (`on_hand - reserved`) and is inclusive: an active policy appears in the low-stock list when available stock is less than or equal to the threshold. A policy with no stock row is treated as zero available. Reservation, release, receipt, allocation and adjustment affect the live quantity through the existing stock ledger; alerts are a query over current committed stock, not a second mutable counter.

## API

- `PUT /api/v1/warehouses/{warehouseId}/stock/{sellableId}/low-stock-policy` creates or updates `{ "threshold": 5, "active": true }`.
- `GET /api/v1/warehouses/{warehouseId}/stock-policies` lists configured policies for a warehouse.
- `GET /api/v1/inventory/low-stock` returns active alerts with warehouse, sellable, available and threshold values.

All routes are ADMIN/SUPERADMIN restricted. Thresholds are non-negative exact decimals with at most four fractional digits. The Backoffice Inventory screen supports threshold management per stock row and displays active low-stock alerts. Delivery to email, Slack, Grafana or other external systems is intentionally delegated to independently installed plugins; Core does not require those providers.

## Verification

`gradlew.bat :artio-inventory:test :artio-rest-api:test --tests io.artio.api.orders.CartControllerShippingTests :artio-rest-api:compileIntegrationTestJava :artio-backoffice:npmBuild --no-daemon` exited 0: 5 inventory domain tests and 3 targeted shipping API tests passed, integration sources compiled, and Angular production build passed. `gradlew.bat :artio-rest-api:integrationTest --tests io.artio.api.catalog.MigrationIntegrationTests.installsCleanlyThroughCurrentSchemaWithoutTenantModel --tests io.artio.api.catalog.CatalogApiIntegrationTests.lowStockPoliciesTrackAvailableStockAndCanBeDisabled --no-daemon` exited 0, but both requested Testcontainers tests were **skipped** because Docker was unavailable. Therefore V28 application and the database-backed policy workflow still require a Docker-capable acceptance run. `git diff --check` passed.
