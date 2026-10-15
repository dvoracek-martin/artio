# Artio PayPal Payments plugin

This Docker extension implements `payment.provider.v1` using PayPal Orders v2, Payments v2 and the webhook signature verification API. It supports hosted approval, capture, partial/full refund, and provider-side reconciliation. Core retains order/payment transactions and webhook deduplication. The payment contract does not support PayPal order cancellation, so the plugin advertises cancellation as unavailable.

Configure PayPal REST app credentials, a webhook ID, and sandbox mode. The manifest defaults to sandbox. For production, configure the live REST application and webhook and explicitly disable sandbox mode. The storefront return includes the PayPal order token; the authenticated Core capture route checks the returned token against the signed-in user's pending payment record before capturing. PayPal webhook signatures are verified through PayPal's `verify-webhook-signature` API using only the specific PayPal headers forwarded by Core.

## Local checks

```powershell
python -m unittest discover -s artio-plugins/paypal-payments -p "test_*.py" -v
docker build -f artio-plugins/paypal-payments/Dockerfile -t artio/paypal-payments:1.0.0 .
```

The tests mock PayPal HTTP and do not claim a sandbox transaction. Run `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-payment-provider-containers.ps1` from the repository root to build and run the Stripe and PayPal images with no network, a read-only root filesystem, dropped Linux capabilities, and bounded resources. The acceptance checks health, diagnostics, authenticated contract handling, capabilities, validation, and credential-free failure behavior. The manifest remains a template until a digest-pinned image is published.

## References

- [PayPal Orders v2](https://developer.paypal.com/api/rest/integration/orders-api)
- [PayPal Payments v2 refunds](https://developer.paypal.com/api/payments/v2/captures-refund)
- [PayPal webhook verification](https://developer.paypal.com/docs/api/webhooks/v1/#verify-webhook-signature_post)
