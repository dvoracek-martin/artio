# Backoffice interaction patterns

Backoffice follows [Artio visual identity](BRAND_IDENTITY.md). Use the shared components below for selections that benefit from search or multiple values; keep short, obvious choices lightweight. These controls use Angular forms without adding UI dependencies or changing API contracts.

## Searchable selections

`SearchSelectComponent` provides a searchable single or multiple selection, selected-value chips, checkmarks, a selected count, Clear/Done actions, empty results, loading and disabled options. It implements `ControlValueAccessor` for `ngModel`. A labelled trigger exposes expanded state and a listbox; options expose selected state. Arrow keys move through options, Escape closes, and focus returns to the trigger. Popovers use fixed positioning and viewport-aware placement so scrollable tables do not clip them. Outside clicks, resize and window scroll close the menu.

Use it for setup and System Settings countries/currencies/locales/time zones and multiple languages, product creation categories/brand, catalog brand/category filters, classification product/brand/categories, category parents, warehouse selection and return restocking. Price and gift-card currency use the same searchable currency list. Shared option sets live in `commerce-options.ts`. Existing inactive classification values remain visible and protected from accidental clearing. Default first-run content languages match Artio's supported product translations: German, French, Italian and English. Resumed values outside the current options remain visible.

`SellableSelectComponent` searches the existing paginated staff catalog API by product name or product SKU, loads variants for at most ten matching products, and shows product/variant names with SKUs. Search requests are debounced; stale requests are cancelled. Selected options are retained when searching for more values. It replaces raw variant UUID entry in pricing, tax-category assignment, stock receipt and bundle composition. Bundles support multiple variants. API payloads still contain UUIDs; users choose readable merchandise labels.

Short boolean/status/type choices and native date/number inputs remain appropriate where search adds no value. Never replace a required field with a custom control without preserving submit-time validation.

## First-run wizard

The [first-run setup wizard](setup/first-run-wizard.md) uses the same controls and visual language. Product creation remains a [separate guided workflow](catalog/backoffice-product-wizard.md).

## Verification (2026-10-15)

The Angular production build passes with strict template checks and the existing bundle budget. Browser checks against an isolated dev-profile API/PostgreSQL installation verified language search, toggle off/on without closing the menu, selection counts and chips, and desktop/390px mobile popover placement. A single pricing variant was found by product SKU and selected by its readable label. A configurable bundle was created from two simultaneously selected variants, confirming that the multi-select values reach the existing UUID-based API correctly. Array-backed `ngModel` values keep stable references, avoiding repeated form updates during Angular change detection.

Catalog data administration uses the persisted workspace locale for categories, brands, product classification, attribute definitions, empty states and API fallback errors. Angular/Vitest tests cover all four supported locales, associated input labels, empty states and localized API failures (8 tests). The Backoffice `check` task runs `npm test`; the production bundle remains at 699.40 kB initial, and `npm audit --audit-level=moderate` against the npm public registry reports zero vulnerabilities.

Product media uses the same workspace locale for image upload, replacement, ordering and deletion. Its loading state suppresses an empty result until the request succeeds; API failures remain visible without implying that a product has no images. EN/DE/IT/FR tests cover the file and alternative-text labels, loading and empty states, accessible image-action labels, unsupported-file validation and API read failures. The media manager remains in its lazy chunk.

Shared searchable country, currency, locale, time-zone and sellable pickers use the workspace locale for their search input names and hints, loading and no-results messages, selected-item counts, clear/done actions, product-variant lookup help and retryable API failures. The searchable menu omits the empty-result message while a request is still running. Its localized screen states are covered for EN/DE/IT/FR in the component suite.
