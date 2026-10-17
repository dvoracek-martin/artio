# Limited promotions (V1)

Discount rules may optionally set `usageLimit` when created through `POST /api/v1/discount-rules`. `null` means unlimited. The Backoffice Pricing page lists the configured cap and lets an administrator activate or deactivate a rule.

Checkout locks active limited rules in stable ID order before repricing the cart. Within the checkout transaction, the resulting order records each applied limited rule once in `pricing_discount_redemptions`. The order row is created as `PENDING`, so it reserves capacity immediately; concurrent checkouts serialize on the same rule rows and cannot exceed the cap. The redemption key `(discount_rule_id, order_id)` makes retries idempotent. If checkout rolls back, its redemption rolls back too. Cancelled orders do not consume capacity, so cancellation releases the reservation.

The current development schema remains the single V1 SQL snapshot. There is no additional migration history for this change.

Acceptance coverage: `CatalogApiIntegrationTests.limitedPromotionIsRedeemedOnlyOnceAcrossConcurrentCheckouts` runs two customer checkouts concurrently against a cap of one and verifies exactly one order receives the discount and exactly one redemption is persisted. Run it with:

```powershell
.\gradlew.bat :artio-rest-api:integrationTest --tests io.artio.api.catalog.CatalogApiIntegrationTests.limitedPromotionIsRedeemedOnlyOnceAcrossConcurrentCheckouts --no-daemon --max-workers=1
```
