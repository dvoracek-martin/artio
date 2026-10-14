# Gift cards v1

Gift cards are stored value in a single ISO-4217 currency. Issuance is restricted to administrators by the API security policy. `POST /api/admin/gift-cards` returns a randomly generated code once; only its SHA-256 digest is persisted. Keep the code secret and deliver it to the recipient through a trusted channel.

## Checkout

The storefront checkout accepts an optional `giftCardCode`. On checkout, Artio locks the owner-scoped pending order and active, unexpired card, then applies up to the smaller of the card balance and remaining order due. The ledger entry, card balance update, and captured `GIFT_CARD` payment are committed atomically. A full tender marks the order paid. A partial tender leaves the order pending and the remaining amount is charged by the configured payment provider. If no external provider is configured, a partial checkout fails and the enclosing transaction rolls the gift-card debit and order creation back.

An authenticated customer may also call `POST /api/gift-cards/redeem` with `code`, `orderId`, and an optional `amount` and `currency`. The order must belong to that customer and be pending. When amount is omitted, Artio applies the maximum available amount up to the order due. Reapplying the same card to the same order is protected by a unique ledger key; checkout itself is replay-safe through its order idempotency key.

```json
{"code":"<gift-card-code>","orderId":"<owned-pending-order-uuid>"}
```

The API response includes the card balance, applied amount, remaining order due, and resulting order status. It does not reveal the stored code hash.

## Refunds

Refunds of `GIFT_CARD` payments restore value to the same card and record the payment refund ID in the gift-card ledger. Partial and full refunds use the existing payment refund API and its idempotency key. The refund transaction and balance restoration share the transaction with payment refund completion. If an administrator has deactivated the card before refund, the operation fails without completing the refund; it requires manual review and the card must be made active before retry. An expired but active card is extended for 365 days when value is returned.

## Limits

- A checkout currently accepts one gift-card code.
- Gift-card currency must equal the order currency; no conversion is applied.
- Gift cards cannot pay an amount below zero, exceed their balance, or be used against a non-pending order.
- Gift-card tender does not support cash-out. Merchandise returns and payment refunds remain distinct workflows.
- Integration tests assert clean-install ledger schema. Execution against PostgreSQL/Testcontainers must be reported separately from compilation when Docker is unavailable.
