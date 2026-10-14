# Backoffice interaction patterns

Backoffice follows [Artio visual identity](BRAND_IDENTITY.md). Use the shared components below for selections that benefit from search or multiple values; keep short, obvious choices lightweight. These controls use Angular forms without adding UI dependencies or changing API contracts.

## Searchable selections

`SearchSelectComponent` provides a searchable single or multiple selection, selected-value chips, checkmarks, a selected count, Clear/Done actions, empty results, loading and disabled options. It implements `ControlValueAccessor` for `ngModel`. A labelled trigger exposes expanded state and a listbox; options expose selected state. Arrow keys move through options, Escape closes, and focus returns to the trigger. Popovers use fixed positioning and viewport-aware placement so scrollable tables do not clip them. Outside clicks, resize and window scroll close the menu.

Use it for setup countries/currencies/locales/time zones and multiple languages, product creation categories/brand, catalog brand/category filters, classification product/brand/categories, category parents, warehouse selection and return restocking. Existing inactive classification values remain visible and protected from accidental clearing. Default first-run content languages match Artio's supported product translations: German, French, Italian and English. Resumed values outside the current options remain visible.

`SellableSelectComponent` searches the existing paginated staff catalog API by product name or product SKU, loads variants for at most ten matching products, and shows product/variant names with SKUs. Search requests are debounced; stale requests are cancelled. Selected options are retained when searching for more values. It replaces raw variant UUID entry in pricing, tax-category assignment, stock receipt and bundle composition. Bundles support multiple variants. API payloads still contain UUIDs; users choose readable merchandise labels.

Short boolean/status/type choices and native date/number inputs remain appropriate where search adds no value. Never replace a required field with a custom control without preserving submit-time validation.

## First-run wizard

The [first-run setup wizard](setup/first-run-wizard.md) uses the same controls and visual language. Product creation remains a [separate guided workflow](catalog/backoffice-product-wizard.md).
