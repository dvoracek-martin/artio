# Artio TWINT Payments via Stripe

This is a separate Artio plugin identity backed by the shared `payments.stripe` extension image and Core's provider-neutral payment contract. It restricts Stripe Checkout's dynamically eligible payment methods to `twint` by configuring `allowedPaymentMethods` as the JSON array `["twint"]`. The merchant must separately have TWINT activated for its Stripe account and use CHF/Swiss checkout; Stripe decides eligibility and performs the PSP transaction. This is not a direct TWINT API integration and does not bypass PSP onboarding or certification.

Configure Stripe credentials and webhook signing secret in the `payments.twint` plugin instance. Set `ARTIO_PAYMENT_PROVIDER=extension` and `ARTIO_PAYMENTS_EXTENSION_PLUGIN_ID=payments.twint`. Stripe webhook events use the ordinary provider webhook URL documented for the payment extension. The manifest image digest is a template until the shared Stripe plugin image is published and pinned.

## Local verification

The implementation is shared with `artio-plugins/stripe-payments`; its unit tests include a test that asserts the generated dynamic payment method filter contains only TWINT and rejects unsupported methods:

```powershell
python -m unittest discover -s artio-plugins/stripe-payments -p "test_*.py" -v
```

No Stripe/TWINT sandbox transaction, account activation, published image, or Docker runtime is claimed.

## Provider references

- [Stripe Checkout Session creation](https://docs.stripe.com/api/checkout/sessions/create)
- [TWINT via a payment service provider](https://www.twint.ch/faq/twint-ueber-einen-payment-service-provider-psp-im-e-commerce-einbinden/)
- [TWINT authorized PSPs](https://www.twint.ch/en/faq/as-a-foreign-merchant-am-i-also-able-to-offer-twint/)
