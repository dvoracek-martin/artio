# Payment refunds API v1

`POST /api/v1/payments/{paymentId}/refund` is restricted to ADMIN and SUPERADMIN. Every request must include an `Idempotency-Key` header containing 1–128 ASCII letters, digits, `.`, `_`, `:`, or `-`.

```http
POST /api/v1/payments/3d5f64a2-f3d5-43a4-aa85-cc1a9992254f/refund
Authorization: Bearer <admin-token>
Idempotency-Key: order-123-refund-1
Content-Type: application/json

{"amount": 12.34}
```

`amount` is optional. Omit it or send `null` to refund the remaining captured amount. A supplied amount must be positive, use the currency's minor unit, and not exceed the unrefunded balance. The API locks the payment row while validating balance and recording the provider result. Stable idempotency maps to the same refund identity and provider key; after a timeout or `502`, retry the same amount with the same key. Reusing a key with a different amount returns `409`.

The `payments` row tracks total captured and cumulative refunded amount; each completed provider operation is recorded in `payment_refunds`. A partial refund leaves the payment in `PARTIALLY_REFUNDED`; a complete refund sets `REFUNDED`. Refunds do not change order fulfillment status or claim that goods were physically returned. Provider failures use the standard API error envelope and leave captured/refunded totals unchanged.

The Backoffice order detail shows payment status and refunded totals. Its refund action confirms the operation and retains the idempotency key in the browser session so a retry after a lost response reuses the original key. Merchandise return authorization, item-level restocking and carrier tracking are separately documented in [order returns v1](../orders/returns-v1.md); a merchandise return does not automatically issue a payment refund.

# Pending order cancellation

An authenticated customer can cancel their own order before payment with `POST /api/v1/payments/orders/{orderId}/cancel`. Artio locks the owner-scoped order and only accepts `PENDING`. When a provider checkout exists, Core first requests cancellation with a stable per-order provider key (or expires the Stripe Checkout Session); only a confirmed cancellation allows the order to become `CANCELLED` and reserved inventory to be released. Ambiguous provider outcomes return a gateway/conflict response and preserve the order and reservation for reconciliation. Paid, shipped and otherwise non-pending orders cannot use this route. Repeating cancellation after the order is already cancelled returns the cancelled state.
