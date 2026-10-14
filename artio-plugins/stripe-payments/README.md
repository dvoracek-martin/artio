# Artio Stripe Payments plugin

This independently buildable Docker plugin implements `payment.provider.v1` for hosted Stripe Checkout. It supports checkout creation, signed event verification, full refunds, checkout expiration and session reconciliation. It does not accept card data in Artio and it does not implement separate authorization/capture; its manifest reports those capabilities as false.

Stripe credentials are supplied through encrypted extension configuration. Test mode defaults to enabled and requires an `sk_test_` key. Live keys require an explicit `testMode=false`. Diagnostics report status only and never make credentials or payment payloads visible.

Configure the Stripe webhook destination as `https://<artio-host>/api/v1/payments/provider/webhook` and send `checkout.session.completed`, `checkout.session.expired`, and `checkout.session.async_payment_failed`. Put the endpoint's `whsec_` secret in the plugin's **Webhook signing secret** setting. The plugin verifies Stripe's timestamped HMAC signature against the original request bytes before returning a neutral event to Core.

## Build and verify locally

From the repository root:

```powershell
python -m unittest discover -s artio-plugins/stripe-payments -p "test_*.py" -v
docker build -f artio-plugins/stripe-payments/Dockerfile -t artio/stripe-payments:1.0.0 .
```

The Python tests mock Stripe HTTP calls and verify request amount conversion, stable idempotency forwarding, webhook HMAC/timestamp checks, and secret-safe errors. They do not claim a provider sandbox transaction or a Docker runtime test.

The source manifest is `manifest.template.json`. Before installation it must be converted to a valid registry digest reference and the registry must be explicitly trusted by the Artio installation. The template deliberately contains a placeholder because publishing an image and inventing its digest would be misleading. Artio's existing Stripe path remains the default until an administrator selects the enabled extension provider.

## Provider references

- [Stripe Checkout Sessions](https://docs.stripe.com/api/checkout/sessions)
- [Create a Checkout Session](https://docs.stripe.com/api/checkout/sessions/create)
- [Stripe webhooks](https://docs.stripe.com/webhooks)
- [Refunds API](https://docs.stripe.com/api/refunds)
