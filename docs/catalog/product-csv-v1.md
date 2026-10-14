# Product CSV import/export v1

Backoffice product exchange uses the UTF-8 CSV header, in this exact order:

```csv
sku,name,description,active
```

Fields follow RFC 4180 quoting rules, including quoted commas, newlines and doubled quotes. A UTF-8 BOM is accepted. SKU, name, and `active` are required; `active` must be `true` or `false`. Blank descriptions become null. Rows with an existing SKU update name, description and active state; new SKUs create products. Archived products are preserved and reported as row errors rather than silently restored or overwritten. Each row is applied independently through catalog use cases and the response includes created, updated and failed counts with row/SKU/reason details. Structural CSV errors reject the entire file before any row is applied.

Import is limited to 5 MiB and 5000 data rows. Export downloads up to 50,000 non-archived products and returns `413` if more are present. CSV values are quoted and spreadsheet formula prefixes are neutralized. The export intentionally includes the basic product fields above; variant, localization, SEO, pricing and inventory data require their own APIs and are not implied to be included.

## Routes

- `POST /api/v1/admin/catalog/products/import` — multipart form field `file`.
- `GET /api/v1/admin/catalog/products/export` — UTF-8 CSV attachment.

Both routes require ADMIN/SUPERADMIN authorization. Backoffice Products provides Import CSV and Export CSV controls; imports show created, updated, failed counts and row-level reasons, then refresh the current product page.

## Verification

`gradlew.bat :artio-rest-api:test --tests io.artio.api.catalog.ProductCsvCodecTests --tests io.artio.api.catalog.AdminCatalogProductQueryControllerTests :artio-rest-api:compileIntegrationTestJava --no-daemon` exited 0: 3 CSV codec and 3 catalog query-boundary tests passed; API and PostgreSQL integration test sources compiled. A separate targeted Testcontainers import/export integration invocation exited 0 with the method **skipped** because Docker was unavailable. Therefore multipart binding and DB upsert/export are not runtime-verified here. `git diff --check` passed.
