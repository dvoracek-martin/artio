import base64
import json
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).parent))
import server
from postfinancecheckout.models.environment import Environment
from postfinancecheckout.models.refund_state import RefundState
from postfinancecheckout.models.transaction_state import TransactionState


CONFIG = {"applicationUserId": "123456", "authenticationKey": "test-authentication-key-that-is-not-real",
          "spaceId": "98765", "testMode": True, "supportedCurrencies": ["CHF", "EUR"]}
ENVELOPE = {"idempotencyKey": "12345678-1234-4234-8234-123456789abc"}


class PostFinanceCheckoutTests(unittest.TestCase):
    @patch("server.api_services")
    def test_checkout_retries_ambiguous_creation_by_stable_merchant_reference(self, services):
        transactions = Mock()
        transactions.get_payment_transactions_search.side_effect = [
            SimpleNamespace(data=[]),
            SimpleNamespace(data=[SimpleNamespace(id=87654)]),
        ]
        transactions.post_payment_transactions.side_effect = TimeoutError("private SDK detail")
        transactions.get_payment_transactions_id_payment_page_url.return_value = "https://checkout.postfinance.ch/pay/87654"
        services.return_value = (98765, transactions, Mock(), Mock())
        payload = {
            "amount": "10.00", "currency": "CHF", "orderId": "order-123",
            "paymentMethodId": "postfinance_checkout",
            "successUrl": "https://shop.example.test/success",
            "cancelUrl": "https://shop.example.test/cancel",
        }

        with self.assertRaises(server.ProviderError) as raised:
            server.operation(CONFIG, "createCheckout", payload, ENVELOPE)
        self.assertEqual(503, raised.exception.status)
        self.assertNotIn("private SDK detail", str(raised.exception))

        result = server.operation(CONFIG, "createCheckout", payload, ENVELOPE)

        self.assertEqual("87654", result["providerReference"])
        self.assertEqual(2, transactions.get_payment_transactions_search.call_count)
        queries = [call.kwargs["query"] for call in transactions.get_payment_transactions_search.call_args_list]
        self.assertEqual(queries[0], queries[1])
        self.assertEqual(1, transactions.post_payment_transactions.call_count)

    def test_capabilities_and_swiss_method_catalog_are_provider_neutral(self):
        self.assertTrue(server.operation({}, "capabilities", {}, ENVELOPE)["hostedCheckout"])
        result = server.operation(CONFIG, "listMethods", {"country": "CH", "currency": "CHF"}, ENVELOPE)
        self.assertEqual("postfinance_checkout", result["methods"][0]["methodId"])
        self.assertEqual(["CHF", "EUR"], result["methods"][0]["currencies"])
        self.assertEqual([], server.operation(CONFIG, "listMethods", {"country": "US", "currency": "USD"}, ENVELOPE)["methods"])

    def test_method_catalog_rejects_invalid_currency_configuration(self):
        with self.assertRaises(server.ProviderError) as raised:
            server.operation({"supportedCurrencies": ["CHF", "bad"]}, "listMethods", {"country": "CH"}, ENVELOPE)
        self.assertEqual(400, raised.exception.status)

    def test_amount_validation_preserves_currency_precision(self):
        self.assertEqual("12.30", format(server.amount_decimal("12.30", "CHF"), ".2f"))
        self.assertEqual("5", server.major_amount(5, "JPY"))
        self.assertEqual("5.125", server.major_amount(5.125, "KWD"))
        for invalid in ("0", "-1", "NaN", "1.001"):
            with self.subTest(invalid=invalid), self.assertRaises(server.ProviderError):
                server.amount_decimal(invalid, "CHF")

    @patch("server.api_services")
    def test_create_checkout_uses_official_transaction_and_hosted_payment_page(self, services):
        transactions = Mock()
        transactions.get_payment_transactions_search.return_value = SimpleNamespace(data=[])
        transactions.post_payment_transactions.return_value = SimpleNamespace(id=87654)
        transactions.get_payment_transactions_id_payment_page_url.return_value = "https://checkout.postfinance.ch/pay/87654"
        services.return_value = (98765, transactions, Mock(), Mock())
        result = server.operation(CONFIG, "createCheckout", {"amount": "12.30", "currency": "CHF",
            "orderId": "order-123", "paymentMethodId": "postfinance_checkout",
            "successUrl": "https://shop.example.test/success", "cancelUrl": "https://shop.example.test/cancel"}, ENVELOPE)
        self.assertEqual("87654", result["providerReference"])
        self.assertEqual("https://checkout.postfinance.ch/pay/87654", result["checkoutUrl"])
        transaction = transactions.post_payment_transactions.call_args.args[1]
        self.assertEqual(Environment.PREVIEW, transaction.environment)
        self.assertEqual("12.3", str(transaction.line_items[0].amount_including_tax))
        self.assertIn("ARTIO-12345678123442348234123456789abc", transaction.merchant_reference)

    @patch("server.api_services")
    def test_checkout_retry_reuses_transaction_found_by_stable_merchant_reference(self, services):
        transactions = Mock()
        transactions.get_payment_transactions_search.return_value = SimpleNamespace(data=[SimpleNamespace(id=87654)])
        transactions.get_payment_transactions_id_payment_page_url.return_value = "https://checkout.postfinance.ch/pay/87654"
        services.return_value = (98765, transactions, Mock(), Mock())
        result = server.operation(CONFIG, "createCheckout", {"amount": "10.00", "currency": "CHF",
            "orderId": "order-123", "paymentMethodId": "postfinance_checkout",
            "successUrl": "https://shop.example.test/success", "cancelUrl": "https://shop.example.test/cancel"}, ENVELOPE)
        self.assertEqual("87654", result["providerReference"])
        transactions.post_payment_transactions.assert_not_called()

    def test_checkout_is_closed_when_credentials_or_return_urls_are_invalid(self):
        payload = {"amount": "10.00", "currency": "CHF", "orderId": "order-123",
                   "paymentMethodId": "postfinance_checkout", "successUrl": "https://shop.test/ok",
                   "cancelUrl": "https://shop.test/cancel"}
        with self.assertRaises(server.ProviderError) as raised:
            server.operation({}, "createCheckout", payload, ENVELOPE)
        self.assertEqual(503, raised.exception.status)
        with self.assertRaises(server.ProviderError) as raised:
            server.operation(CONFIG, "createCheckout", {**payload, "successUrl": "http://shop.test/ok"}, ENVELOPE)
        self.assertEqual(400, raised.exception.status)

    @patch("server.api_services")
    def test_cancel_uses_transaction_void_api(self, services):
        transactions = Mock()
        services.return_value = (98765, transactions, Mock(), Mock())
        result = server.operation(CONFIG, "cancel", {"providerReference": "87654"}, ENVELOPE)
        transactions.post_payment_transactions_id_void_online.assert_called_once_with(87654, 98765)
        self.assertEqual("CANCELLED", result["state"])

    @patch("server.api_services")
    def test_refund_uses_stable_external_id_and_requires_provider_success(self, services):
        refunds = Mock()
        refunds.post_payment_refunds.return_value = SimpleNamespace(id=4567, state=RefundState.SUCCESSFUL)
        services.return_value = (98765, Mock(), refunds, Mock())
        result = server.operation(CONFIG, "refund", {"providerReference": "87654", "amount": "3.20", "currency": "CHF"}, ENVELOPE)
        refund_request = refunds.post_payment_refunds.call_args.args[1]
        self.assertEqual("12345678-1234-4234-8234-123456789abc", refund_request.external_id)
        self.assertEqual("3.20", result["amount"])
        self.assertEqual("REFUNDED", result["state"])

    @patch("server.api_services")
    def test_refund_preserves_three_decimal_currency_amount(self, services):
        refunds = Mock()
        refunds.post_payment_refunds.return_value = SimpleNamespace(id=4567, state=RefundState.SUCCESSFUL)
        services.return_value = (98765, Mock(), refunds, Mock())
        result = server.operation({**CONFIG, "supportedCurrencies": ["KWD"]}, "refund",
            {"providerReference": "87654", "amount": "3.125", "currency": "KWD"}, ENVELOPE)
        self.assertEqual("3.125", result["amount"])

    @patch("server.api_services")
    def test_unconfirmed_refund_is_an_ambiguous_retryable_failure(self, services):
        refunds = Mock()
        refunds.post_payment_refunds.return_value = SimpleNamespace(id=4567, state=RefundState.PENDING)
        services.return_value = (98765, Mock(), refunds, Mock())
        with self.assertRaises(server.ProviderError) as raised:
            server.operation(CONFIG, "refund", {"providerReference": "87654", "amount": "3.20", "currency": "CHF"}, ENVELOPE)
        self.assertEqual(503, raised.exception.status)

    @patch("server.read_transaction")
    def test_reconcile_uses_provider_state_and_settled_money(self, read_transaction):
        read_transaction.return_value = SimpleNamespace(id=87654, state=TransactionState.COMPLETED,
                                                        total_settled_amount=12.3, currency="CHF")
        result = server.operation(CONFIG, "reconcile", {"providerReference": "87654"}, ENVELOPE)["result"]
        self.assertEqual("CAPTURED", result["state"])
        self.assertEqual("12.30", result["amount"])
        self.assertEqual("CHF", result["currency"])

    @patch("server.read_transaction")
    @patch("server.api_services")
    def test_webhook_checks_official_ecdsa_signature_then_reads_authoritative_transaction(self, services, read_transaction):
        keys = Mock()
        keys.is_content_valid.return_value = True
        services.return_value = (98765, Mock(), Mock(), keys)
        read_transaction.return_value = SimpleNamespace(id=87654, state=TransactionState.COMPLETED,
                                                        total_settled_amount=12.3, currency="CHF")
        raw = json.dumps({"eventId": 456, "entityId": 87654, "spaceId": 98765,
            "listenerEntityTechnicalName": "Transaction", "state": "COMPLETED"}).encode()
        result = server.operation(CONFIG, "verifyWebhook", {"rawBodyBase64": base64.b64encode(raw).decode(),
            "headers": {"x-signature": "algorithm=SHA256withECDSA, keyId=key-1, signature=valid"}}, ENVELOPE)
        keys.is_content_valid.assert_called_once_with("algorithm=SHA256withECDSA, keyId=key-1, signature=valid", raw.decode())
        self.assertEqual("CAPTURED", result["state"])
        self.assertEqual("456", result["providerEventId"])
        self.assertEqual("87654", result["transactionReference"])

    @patch("server.api_services")
    def test_webhook_rejects_bad_signature_before_trusting_transaction_id(self, services):
        keys = Mock()
        keys.is_content_valid.return_value = False
        services.return_value = (98765, Mock(), Mock(), keys)
        raw = json.dumps({"eventId": 456, "entityId": 87654, "spaceId": 98765,
            "listenerEntityTechnicalName": "Transaction", "state": "COMPLETED"}).encode()
        with self.assertRaises(server.ProviderError) as raised:
            server.operation(CONFIG, "verifyWebhook", {"rawBodyBase64": base64.b64encode(raw).decode(),
                "headers": {"x-signature": "algorithm=SHA256withECDSA, keyId=key-1, signature=invalid"}}, ENVELOPE)
        self.assertEqual(400, raised.exception.status)


if __name__ == "__main__":
    unittest.main()
