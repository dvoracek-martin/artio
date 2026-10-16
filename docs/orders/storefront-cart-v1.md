# Storefront catalog and cart API usage

The Storefront reads catalog content exclusively from Artio Core. Published products come from `GET /api/v1/products`; category and brand filters use `GET /api/v1/categories` and `GET /api/v1/brands`. Localized product copy, variants, and media use the product localization, variant, and media routes. Product detail pages resolve published SEO slugs through `GET /api/v1/public/products/{slug}?locale=…`. Current prices come from `POST /api/v1/pricing/quote`; the Storefront never supplies catalog records or sample merchandise.

Core does not expose offset pagination or category/brand catalog filters today. The Storefront filters the published catalog and paginates it in the browser. Product media, translations, variants, and current prices are loaded for visible cards; price sorting resolves catalog prices when selected.

## Customer cart routes

All cart routes require an authenticated customer.

- `GET /api/v1/cart` returns the customer's lines, including a unique `itemId`, `variantId`, `quantity`, `unitPrice`, `currency`, and optional `bundleRef`/`bundleId`.
- `POST /api/v1/cart/items` adds a variant using current pricing context (`market`, `channel`, `jurisdiction`).
- `PUT /api/v1/cart/items/{variantId}` sets the quantity on that standalone cart line and reprices it in the submitted context. Bundle component quantities are controlled by their bundle configuration.
- `DELETE /api/v1/cart/items/lines/{itemId}` removes one standalone line. If the line belongs to a bundle, it removes the complete configured bundle and all its component lines.
- `POST /api/v1/cart/bundles` adds a server-validated bundle configuration after the Storefront obtains a current quote from `POST /api/v1/bundles/{id}/quote`.
- `POST /api/v1/cart/checkout` creates an order from fresh Core pricing, tax, inventory, gift-card, shipping, and idempotency checks. Payment is completed through a hosted provider session; a redirect alone is not treated as confirmation.

The older `DELETE /api/v1/cart/items/{variantId}` remains available for clients that remove all matching standalone and bundle component lines. New Storefront code uses the precise line route above.

## Shipping markets

Checkout market and currency selection mirrors `CartController.marketForCountry`: CH/CHF, US/USD, CZ/CZK, and DE/AT/FR/NL/BE/ES/IT/PL/SK in EUR. Shipping-method discovery and quotes are revalidated by Core at checkout. The Storefront sends a declared merchandise value when cart lines share the selected market currency; Core remains authoritative for the accepted shipping quote and final order totals.

Order history, customer-visible timelines, and merchandise returns use the customer order, timeline, and returns routes documented in [Order returns API v1](returns-v1.md). Return requests retain the API's required idempotency key across a retry of the same order line and quantity.
