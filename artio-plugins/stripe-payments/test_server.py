import base64
import hashlib
import hmac
import json
import time
import unittest
from unittest.mock import patch

import server


class StripeExtensionTests(unittest.TestCase):
    def test_checkout_uses_core_identity_and_stripe_idempotency_key(self):
        response = {"id": "cs_test_123", "url": "https://checkout.stripe.test/session"}
        with patch.object(server, "stripe_request", return_value=response) as request:
            result = server.operation({"stripeSecretKey": "sk_test_secret"}, "createCheckout", {
                "orderId": "order-123", "amount": "12.50", "currency": "CHF",
                "successUrl": "https://shop.test/success", "cancelUrl": "https://shop.test/cancel",
                "metadata": {"expiresAt": "2030-01-01T12:00:00Z"},
            }, {"idempotencyKey": "3b241101-e2bb-4255-8caf-4136c566a962"})

        self.assertEqual("cs_test_123", result["providerReference"])
        self.assertEqual("PENDING", result["state"])
        args = request.call_args.args
        self.assertEqual("POST", args[2])
        self.assertEqual("1250", args[3]["line_items[0][price_data][unit_amount]"])
        self.assertEqual("3b241101-e2bb-4255-8caf-4136c566a962", args[4])

    def test_currency_minor_units_reject_precision_loss(self):
        self.assertEqual(123, server.minor_units("123", "JPY"))
        self.assertEqual(1250, server.minor_units("12.500", "CHF"))
        with self.assertRaises(server.ProviderError):
            server.minor_units("12.501", "CHF")

    def test_webhook_signature_checks_tolerance_and_event_payload(self):
        secret = "whsec_test"
        timestamp = str(int(time.time()))
        body = json.dumps({"id": "evt_123", "type": "checkout.session.completed", "data": {
            "object": {"id": "cs_123", "payment_status": "paid"}}}).encode()
        signed = timestamp.encode() + b"." + body
        signature = hmac.new(secret.encode(), signed, hashlib.sha256).hexdigest()
        header = f"t={timestamp},v1={signature}"
        self.assertTrue(server.verify_stripe_signature(secret, header, body))
        self.assertFalse(server.verify_stripe_signature(secret, header, body, now=int(timestamp) + 400))

        result = server.operation({"webhookSecret": secret}, "verifyWebhook", {
            "signature": header, "rawBodyBase64": base64.b64encode(body).decode(),
        }, {})
        self.assertEqual({"providerEventId": "evt_123", "eventType": "checkout.session.completed",
                          "providerReference": "cs_123", "state": "CAPTURED", "verified": True}, result)

    def test_credential_errors_never_return_secret_values(self):
        with self.assertRaises(server.ProviderError) as error:
            server.stripe_request({"stripeSecretKey": ""}, "/v1/checkout/sessions")
        self.assertNotIn("stripeSecretKey", str(error.exception))


if __name__ == "__main__":
    unittest.main()
