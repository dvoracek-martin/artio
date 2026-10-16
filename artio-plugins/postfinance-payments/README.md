# Artio PostFinance Checkout plugin

This independently buildable Docker plugin implements `payment.provider.v1` using PostFinance Checkout's official Python SDK and hosted payment page. It supports checkout creation, PREVIEW/LIVE environment selection, signed ECDSA webhook verification with PostFinance's published encryption keys, transaction lookup/reconciliation, online voids, and online refunds. Card data stays on the provider's hosted page. Separate authorization/capture is not advertised.

Configure an API application user ID, its authentication key, and the Checkout space ID in the encrypted plugin configuration. The API application user needs the narrowest permissions PostFinance documents for transaction create/read/void, refund creation and webhook encryption key read. `testMode` defaults to PREVIEW. Currency defaults to CHF and can be limited to currencies enabled in the provider space. Checkout transactions are retried by searching the space for the stable Core idempotency-derived merchant reference before creating a new transaction; refunds use the stable idempotency key as PostFinance's `externalId`.

Create a Transaction webhook listener in the configured PostFinance Checkout space and configure its URL as `https://<artio-host>/api/v1/payments/provider/webhook` (or the selected v2 binding webhook URL shown by Artio). Enable the new signed webhook payload mechanism. Core passes the original raw payload and the `X-Signature` header to the plugin. The plugin verifies ECDSA against the key ID through PostFinance's Webhook Encryption API, confirms the event belongs to the configured space, then fetches the transaction and reports provider state and settled amount to Core. Browser return URLs never mark an order as paid.

## Local checks

```powershell
python -m pip install -r artio-plugins/postfinance-payments/requirements.txt
python -m unittest discover -s artio-plugins/postfinance-payments -p "test_*.py" -v
docker build -f artio-plugins/postfinance-payments/Dockerfile -t artio/postfinance-payments:local .
```

The tests use mocked SDK calls, so they validate REST mapping, idempotency, ECDSA-verifier invocation and fail-closed behavior without claiming a sandbox charge. The image requires network access to PostFinance Checkout at runtime. Remove the local image after manual testing with `docker image rm artio/postfinance-payments:local`; the release acceptance script removes only its own run-tagged image and container.

## Provider references

- [PostFinance Checkout REST and SDK documentation](https://checkout.postfinance.ch/doc)
- [PostFinance Checkout Python SDK](https://github.com/pfpayments/python-sdk)
- [PostFinance Checkout webhook signing and delivery](https://checkout.postfinance.ch/en-us/doc/webhooks)
- [Hosted PostFinance payment page and refund capabilities](https://checkout.postfinance.ch/en/connectors/1672106583000/postfinance-pay)
