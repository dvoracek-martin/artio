import base64
import json
import unittest
from unittest.mock import patch

import server


class FakeResponse:
    def __init__(self, body):
        self.body = json.dumps(body).encode()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self, _limit):
        return self.body


class PayPalTests(unittest.TestCase):
    def setUp(self):
        self.config = {"clientId": "sandbox-client", "clientSecret": "sandbox-secret",
                       "webhookId": "WH-test123", "sandboxMode": True}
        self.envelope = {"idempotencyKey": "stable-order-key"}

    def test_currency_precision_is_validated(self):
        self.assertEqual("10.25", server.amount_text("10.25", "CHF"))
        self.assertEqual("10", server.amount_text("10", "JPY"))
        with self.assertRaises(server.ProviderError):
            server.amount_text("10.001", "CHF")

    def test_transport_outage_is_retryable_and_does_not_leak_provider_details(self):
        with patch.object(server, "urlopen", side_effect=server.URLError("private provider response")):
            with self.assertRaises(server.ProviderError) as raised:
                server.request_json("https://api-m.sandbox.paypal.com/v2/checkout/orders")

        self.assertEqual(503, raised.exception.status)
        self.assertNotIn("private provider response", str(raised.exception))

    def test_caller_retry_reuses_paypal_request_id_after_ambiguous_failure(self):
        idempotency_key = "stable-order-key"
        with patch.object(server, "access_token", return_value="sandbox-access-token"), \
                patch.object(server, "request_json", side_effect=[
                    server.ProviderError(503, "temporary outage"),
                    {"id": "ORDER-12345678"},
                ]) as request:
            with self.assertRaises(server.ProviderError) as raised:
                server.paypal_request(
                    self.config, "/v2/checkout/orders", "POST", {}, idempotency_key
                )
            self.assertEqual(503, raised.exception.status)

            result = server.paypal_request(
                self.config, "/v2/checkout/orders", "POST", {}, idempotency_key
            )

        self.assertEqual("ORDER-12345678", result["id"])
        self.assertEqual(2, request.call_count)
        self.assertEqual(
            [idempotency_key, idempotency_key],
            [call.args[2]["PayPal-Request-Id"] for call in request.call_args_list],
        )

    def test_discovers_paypal_and_rejects_a_different_selected_method(self):
        methods = server.operation(self.config, "listMethods", {"country": "CH", "currency": "CHF"}, self.envelope)
        self.assertEqual("paypal", methods["methods"][0]["methodId"])
        self.assertIn("CHF", methods["methods"][0]["currencies"])
        with self.assertRaisesRegex(server.ProviderError, "not enabled"):
            server.operation(self.config, "createCheckout", {"paymentMethodId": "card"}, self.envelope)

    def test_reconciliation_does_not_claim_unfinished_order_as_captured(self):
        with patch.object(server, "paypal_request", return_value={"status": "APPROVED", "purchase_units": []}):
            result = server.operation(self.config, "reconcile", {"providerReference": "ORDER-12345678"}, self.envelope)

        self.assertIsNone(result["result"])

    def test_reconciliation_returns_exact_provider_amount_and_currency(self):
        response = {"status": "COMPLETED", "purchase_units": [{"payments": {"captures": [
            {"amount": {"value": "12.34", "currency_code": "USD"}}
        ]}}]}
        with patch.object(server, "paypal_request", return_value=response):
            result = server.operation(self.config, "reconcile", {"providerReference": "ORDER-12345678"}, self.envelope)
        self.assertEqual("12.34", result["result"]["amount"])
        self.assertEqual("USD", result["result"]["currency"])

    def test_refund_uses_requested_amount_and_stable_provider_key(self):
        details = {"purchase_units": [{"payments": {"captures": [{"id": "CAPTURE-12345678"}]}}]}
        refund = {"id": "REFUND-12345678", "status": "COMPLETED"}
        envelope = {"idempotencyKey": "stable-refund-key"}
        with patch.object(server, "paypal_request", side_effect=[details, refund]) as request:
            result = server.operation(self.config, "refund", {
                "providerReference": "ORDER-12345678", "amount": "7.25", "currency": "CHF",
            }, envelope)

        self.assertEqual("REFUND-12345678", result["providerReference"])
        self.assertEqual("REFUNDED", result["state"])
        self.assertEqual("POST", request.call_args_list[1].args[2])
        self.assertEqual({"amount": {"value": "7.25", "currency_code": "CHF"}}, request.call_args_list[1].args[3])
        self.assertEqual("stable-refund-key", request.call_args_list[1].args[4])

    @patch("server.urlopen", side_effect=[
        FakeResponse({"access_token": "access-token"}),
        FakeResponse({"id": "ORDER-12345678", "links": [{"rel": "payer-action", "href": "https://www.paypal.com/checkout?token=ORDER-12345678"}]})
    ])
    def test_checkout_uses_paypal_request_id_and_returns_approval_link(self, request):
        result = server.operation(self.config, "createCheckout", {
            "orderId": "order-1", "amount": "42.50", "currency": "CHF",
            "paymentMethodId": "paypal",
            "successUrl": "https://shop.test/checkout?success=true", "cancelUrl": "https://shop.test/orders"
        }, self.envelope)
        self.assertEqual("ORDER-12345678", result["providerReference"])
        self.assertIn("checkout?token=", result["checkoutUrl"])
        order_request = request.call_args_list[1].args[0]
        self.assertEqual("stable-order-key", order_request.get_header("Paypal-request-id"))
        payload = json.loads(order_request.data)
        self.assertEqual("42.50", payload["purchase_units"][0]["amount"]["value"])

    @patch("server.urlopen", side_effect=[
        FakeResponse({"access_token": "access-token"}),
        FakeResponse({"verification_status": "SUCCESS"})
    ])
    def test_webhook_verification_uses_paypal_header_bag_and_configured_webhook(self, request):
        event = {"id": "WH-EVENT-1", "event_type": "PAYMENT.CAPTURE.COMPLETED",
                 "resource": {"id": "CAPTURE-123", "amount": {"value": "42.50", "currency_code": "USD"},
                              "supplementary_data": {"related_ids": {"order_id": "ORDER-12345678"}}}}
        payload = {"headers": {"paypal-transmission-id": "tx", "paypal-transmission-time": "time",
                    "paypal-transmission-sig": "signature", "paypal-cert-url": "https://api.paypal.com/cert",
                    "paypal-auth-algo": "SHA256withRSA"},
                   "rawBodyBase64": base64.b64encode(json.dumps(event).encode()).decode()}
        result = server.operation(self.config, "verifyWebhook", payload, self.envelope)
        self.assertTrue(result["verified"])
        self.assertEqual("ORDER-12345678", result["providerReference"])
        self.assertEqual("CAPTURED", result["state"])
        self.assertEqual("42.50", result["amount"])
        self.assertEqual("USD", result["currency"])
        verification_request = request.call_args_list[1].args[0]
        verification = json.loads(verification_request.data)
        self.assertEqual("WH-test123", verification["webhook_id"])
        self.assertEqual("tx", verification["transmission_id"])

    @patch("server.urlopen", side_effect=[
        FakeResponse({"access_token": "access-token"}),
        FakeResponse({"verification_status": "SUCCESS"})
    ])
    def test_reversed_capture_webhook_carries_order_and_capture_references(self, request):
        event = {"id": "WH-REVERSED-1", "event_type": "PAYMENT.CAPTURE.REVERSED",
                 "resource": {"id": "CAPTURE-12345678", "amount": {"value": "42.50", "currency_code": "USD"},
                              "supplementary_data": {"related_ids": {"order_id": "ORDER-12345678",
                                                                          "capture_id": "CAPTURE-12345678"}}}}
        payload = {"headers": {"paypal-transmission-id": "tx", "paypal-transmission-time": "time",
                    "paypal-transmission-sig": "signature", "paypal-cert-url": "https://api.paypal.com/cert",
                    "paypal-auth-algo": "SHA256withRSA"},
                   "rawBodyBase64": base64.b64encode(json.dumps(event).encode()).decode()}

        result = server.operation(self.config, "verifyWebhook", payload, self.envelope)

        self.assertEqual("REVERSED", result["state"])
        self.assertEqual("ORDER-12345678", result["providerReference"])
        self.assertEqual("CAPTURE-12345678", result["transactionReference"])
        self.assertEqual("42.50", result["amount"])


if __name__ == "__main__":
    unittest.main()
