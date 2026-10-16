# Storefront account and language behavior

Customer account settings use Core's authenticated account routes:

- `GET /api/v1/users/me` loads the current profile.
- `PUT /api/v1/users/me/profile` updates first and last name after current-password confirmation.
- `POST /api/v1/users/me/email-change` starts email change; the new address is not active until confirmed through `POST /api/v1/auth/email-change/complete`.
- `PUT /api/v1/users/me/password` updates the password after current-password confirmation and invalidates existing sessions.
- `POST /api/v1/auth/customer-password-reset/request` sends a 30-minute recovery link to an active customer account. `POST /api/v1/auth/customer-password-reset/complete` consumes that one-time link. The request response does not reveal whether an account exists. A development-only instance without email delivery may return a local token.

Recovery tokens reuse `artio_password_reset_tokens`, are stored only as SHA-256 hashes, expire after 30 minutes, and are invalidated after use or a newer request. Customer reset completion only accepts active `CUSTOMER` accounts. `ARTIO_STOREFRONT_URL` sets the public origin used in recovery links; it defaults to `http://localhost:4200` for local development.

Store language selection supports English, German, French and Italian and persists in this browser. When there is no saved selection, the storefront reads the installation's configured default locale from `GET /api/v1/public/config`. Core has no per-customer preferred-locale field or customer endpoint for changing the installation default, so the storefront does not claim to sync a language preference across devices or change the store-wide default.

The catalog continues to use published products and active categories and brands from Core. Search, category/brand filters, sorting and page navigation operate on that API-provided catalog; they do not add sample catalog content.
