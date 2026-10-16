import base64
import hashlib
import hmac
import json
import time
import unittest
from unittest.mock import MagicMock, patch

import server


class StripeExtensionTests(unittest.TestCase):
    def test_ambiguous_network_failure_is_redacted_and_caller_retry_reuses_key(self):
        response = MagicMock()
        response.__enter__.return_value.read.return_value = b'{"id":"cs_test_retry"}'
        idempotency_key = "3b241101-e2bb-4255-8caf-4136c566a962"
        with patch.object(server, "urlopen", side_effect=[
            server.URLError("private provider response"), response,
        ]) as urlopen:
            with self.assertRaises(server.ProviderError) as raised:
                server.stripe_request(
                    {"stripeSecretKey": "sk_test_secret"},
                    "/v1/checkout/sessions",
                    "POST",
                    {"mode": "payment"},
                    idempotency_key,
                )
            self.assertEqual(503, raised.exception.status)
            self.assertNotIn("private provider response", str(raised.exception))

            result = server.stripe_request(
                {"stripeSecretKey": "sk_test_secret"},
                "/v1/checkout/sessions",
                "POST",
                {"mode": "payment"},
                idempotency_key,
            )

        self.assertEqual("cs_test_retry", result["id"])
        self.assertEqual(2, urlopen.call_count)
        requests = [call.args[0] for call in urlopen.call_args_list]
        self.assertEqual([idempotency_key, idempotency_key], [
            request.get_header("Idempotency-key") for request in requests
        ])

    def test_cancel_expires_checkout_with_stable_idempotency_key(self):
        response = {"id": "cs_test_123", "status": "expired", "payment_status": "unpaid"}
        with patch.object(server, "stripe_request", return_value=response) as request:
            result = server.operation({"stripeSecretKey": "sk_test_secret"}, "cancel", {
                "providerReference": "cs_test_123", "idempotencyKey": "cancel-order-123",
            }, {"idempotencyKey": "cancel-order-123"})

        self.assertEqual("CANCELLED", result["state"])
        self.assertEqual("cs_test_123", result["providerReference"])
        self.assertEqual("cancel-order-123", request.call_args.args[4])

    def test_refund_sends_requested_partial_amount_with_stable_idempotency(self):
        session = {"payment_intent": "pi_test_123"}
        refund = {"id": "re_test_123", "status": "succeeded", "failure_reason": None}
        with patch.object(server, "stripe_request", side_effect=[session, refund]) as request:
            result = server.operation({"stripeSecretKey": "sk_test_secret"}, "refund", {
                "providerReference": "cs_test_123", "amount": "12.34", "currency": "CHF",
            }, {"idempotencyKey": "refund-stable-123"})

        self.assertEqual("REFUNDED", result["state"])
        self.assertEqual("re_test_123", result["providerReference"])
        self.assertEqual({"payment_intent": "pi_test_123", "amount": 1234}, request.call_args_list[1].args[3])
        self.assertEqual("refund-stable-123", request.call_args_list[1].args[4])

    def test_checkout_uses_core_identity_and_stripe_idempotency_key(self):
        response = {"id": "cs_test_123", "url": "https://checkout.stripe.test/session"}
        with patch.object(server, "stripe_request", return_value=response) as request:
            result = server.operation({"stripeSecretKey": "sk_test_secret"}, "createCheckout", {
                "orderId": "order-123", "amount": "12.50", "currency": "CHF",
            "paymentMethodId": "card",
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

    def test_async_payment_success_webhook_is_a_capture(self):
        secret = "whsec_test"
        timestamp = str(int(time.time()))
        body = json.dumps({"id": "evt_async", "type": "checkout.session.async_payment_succeeded", "data": {
            "object": {"id": "cs_async", "payment_status": "paid", "amount_total": 850, "currency": "chf"}}}).encode()
        digest = hmac.new(secret.encode(), timestamp.encode() + b"." + body, hashlib.sha256).hexdigest()

        result = server.operation({"webhookSecret": secret}, "verifyWebhook", {
            "headers": {"stripe-signature": f"t={timestamp},v1={digest}"},
            "rawBodyBase64": base64.b64encode(body).decode(),
        }, {})

        self.assertEqual("CAPTURED", result["state"])
        self.assertEqual("8.50", result["amount"])
        self.assertEqual("cs_async", result["providerReference"])

    def test_reconciliation_returns_exact_major_units(self):
        session = {"id": "cs_123", "status": "complete", "payment_status": "paid",
                   "amount_total": 1234, "currency": "usd"}
        with patch.object(server, "stripe_request", return_value=session):
            result = server.operation({"stripeSecretKey": "sk_test_secret"}, "reconcile",
                                      {"providerReference": "cs_123"}, {})
        self.assertEqual("12.34", result["result"]["amount"])
        self.assertEqual("USD", result["result"]["currency"])

    def test_dynamic_payment_method_filter_can_select_twint_only(self):
        response = {"id": "cs_test_twint", "url": "https://checkout.stripe.test/twint"}
        with patch.object(server, "stripe_request", return_value=response) as request:
            server.operation({"stripeSecretKey": "sk_test_secret", "allowedPaymentMethods": ["twint"]}, "createCheckout", {
                "orderId": "order-ch", "amount": "12.50", "currency": "CHF",
            "paymentMethodId": "twint",
                "successUrl": "https://shop.test/success", "cancelUrl": "https://shop.test/cancel", "metadata": {},
            }, {"idempotencyKey": "stable-twint-order"})
        form = request.call_args.args[3]
        self.assertEqual(["twint"], form["payment_method_types[]"])
        with self.assertRaises(server.ProviderError):
            server.operation({"stripeSecretKey": "sk_test_secret", "allowedPaymentMethods": ["unknown"]}, "createCheckout", {
                "orderId": "order-ch", "amount": "12.50", "currency": "CHF",
                "successUrl": "https://shop.test/success", "cancelUrl": "https://shop.test/cancel", "metadata": {},
            }, {"idempotencyKey": "stable-twint-order"})

    def test_discovers_configured_methods_and_checkout_requires_selected_method(self):
        methods = server.operation({"allowedPaymentMethods": ["card", "twint"]}, "listMethods",
                                   {"country": "CH", "currency": "CHF"}, {})
        self.assertEqual(["card", "twint"], [method["methodId"] for method in methods["methods"]])
        response = {"id": "cs_test_twint", "url": "https://checkout.stripe.test/twint"}
        with patch.object(server, "stripe_request", return_value=response) as request:
            server.operation({"stripeSecretKey": "sk_test_secret", "allowedPaymentMethods": ["card", "twint"]}, "createCheckout", {
                "orderId": "order-ch", "amount": "12.50", "currency": "CHF", "paymentMethodId": "twint",
                "successUrl": "https://shop.test/success", "cancelUrl": "https://shop.test/cancel", "metadata": {},
            }, {"idempotencyKey": "stable-twint-order"})
        self.assertEqual(["twint"], request.call_args.args[3]["payment_method_types[]"])
        with self.assertRaises(server.ProviderError):
            server.operation({"stripeSecretKey": "sk_test_secret", "allowedPaymentMethods": ["card"]}, "createCheckout", {
                "orderId": "order-ch", "amount": "12.50", "currency": "CHF", "paymentMethodId": "twint",
                "successUrl": "https://shop.test/success", "cancelUrl": "https://shop.test/cancel", "metadata": {},
            }, {"idempotencyKey": "stable-twint-order"})

    def test_webhook_signature_checks_tolerance_and_event_payload(self):
        secret = "whsec_test"
        timestamp = str(int(time.time()))
        body = json.dumps({"id": "evt_123", "type": "checkout.session.completed", "data": {
            "object": {"id": "cs_123", "payment_status": "paid", "amount_total": 1250, "currency": "chf"}}}).encode()
        signed = timestamp.encode() + b"." + body
        signature = hmac.new(secret.encode(), signed, hashlib.sha256).hexdigest()
        header = f"t={timestamp},v1={signature}"
        self.assertTrue(server.verify_stripe_signature(secret, header, body))
        self.assertFalse(server.verify_stripe_signature(secret, header, body, now=int(timestamp) + 400))

        result = server.operation({"webhookSecret": secret}, "verifyWebhook", {
            "headers": {"stripe-signature": header}, "rawBodyBase64": base64.b64encode(body).decode(),
        }, {})
        self.assertEqual({"providerEventId": "evt_123", "eventType": "checkout.session.completed",
                          "providerReference": "cs_123", "transactionReference": None, "state": "CAPTURED", "amount": "12.50",
                          "currency": "CHF", "verified": True}, result)

    def test_dispute_events_are_correlated_to_payment_intent(self):
        secret = "whsec_test"
        timestamp = str(int(time.time()))
        body = json.dumps({"id": "evt_dispute", "type": "charge.dispute.created", "data": {
            "object": {"id": "dp_123", "payment_intent": "pi_123", "amount": 725,
                       "currency": "chf", "status": "needs_response"}}}).encode()
        digest = hmac.new(secret.encode(), timestamp.encode() + b"." + body, hashlib.sha256).hexdigest()

        result = server.operation({"webhookSecret": secret}, "verifyWebhook", {
            "headers": {"stripe-signature": f"t={timestamp},v1={digest}"},
            "rawBodyBase64": base64.b64encode(body).decode(),
        }, {})

        self.assertEqual("DISPUTED", result["state"])
        self.assertEqual("dp_123", result["providerReference"])
        self.assertEqual("pi_123", result["transactionReference"])
        self.assertEqual("7.25", result["amount"])

    def test_lost_dispute_is_a_reversal(self):
        secret = "whsec_test"
        timestamp = str(int(time.time()))
        body = json.dumps({"id": "evt_lost", "type": "charge.dispute.closed", "data": {
            "object": {"id": "dp_123", "payment_intent": "pi_123", "amount": 725,
                       "currency": "chf", "status": "lost"}}}).encode()
        digest = hmac.new(secret.encode(), timestamp.encode() + b"." + body, hashlib.sha256).hexdigest()

        result = server.operation({"webhookSecret": secret}, "verifyWebhook", {
            "headers": {"stripe-signature": f"t={timestamp},v1={digest}"},
            "rawBodyBase64": base64.b64encode(body).decode(),
        }, {})

        self.assertEqual("DISPUTE_LOST", result["state"])
        self.assertEqual("dp_123", result["providerReference"])

    def test_credential_errors_never_return_secret_values(self):
        with self.assertRaises(server.ProviderError) as error:
            server.stripe_request({"stripeSecretKey": ""}, "/v1/checkout/sessions")
        self.assertNotIn("stripeSecretKey", str(error.exception))


if __name__ == "__main__":
    unittest.main()
