# Backoffice product creation

The Backoffice uses the shared [Artio visual identity](../BRAND_IDENTITY.md): Sora typography, dark navy surfaces and cyan–indigo–violet primary accents. Navigation, readable tables, responsive forms and focus states use a dedicated Backoffice layout layer at `artio-backoffice/src/backoffice.css`.

`/products` is the searchable catalog. `/products/new` is a separate four-step creation page: essentials and first variant, category/brand/typed attributes, image upload and ordering, then review. Categories can be created and selected inline. JPEG/PNG previews stay in browser memory until creation; the first image is the cover. Each image accepts alternative text. Prices and stock remain in their existing administration areas.

`POST /api/v1/products/drafts` (ADMIN/SUPERADMIN) accepts `name`, `description`, `sku`, `variantName`, `variantSku`, `brandId`, `categoryIds` and `attributes`. At least one category is required. It creates the product as an inactive draft together with its first variant and classification in one database transaction. SKU or metadata errors roll back the entire draft. Existing `POST /products` semantics are unchanged.

The browser uploads selected images sequentially through the existing validated product media API. Activation, if selected, happens after all uploads succeed. During a failed upload, the current wizard retains the created product ID and completed uploads; Retry skips both product creation and completed uploads. Returning to Products leaves the draft available for the existing media manager. Refreshing or leaving the page discards unsaved browser fields/files; uploaded media and the saved draft remain on the server.

For standalone Angular development, `npm start` in `artio-backoffice` listens on port 4201 and proxies `/api` to `http://localhost:8080`. Set `ARTIO_DEV_API_URL` before starting it to use another API endpoint. Deployed nginx continues to provide same-origin API access.
