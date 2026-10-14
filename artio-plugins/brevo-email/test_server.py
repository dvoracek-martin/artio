import json
import unittest
from unittest.mock import patch

import server


class FakeResponse:
    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self, _limit):
        return b'{"messageId":"<message-123>"}'


class BrevoTests(unittest.TestCase):
    def setUp(self):
        self.config = {"apiKey": "xkeysib-test-secret", "senderEmail": "orders@example.test", "senderName": "Artio"}
        self.message = {"idempotencyKey": "stable-key", "channel": "EMAIL", "destination": "buyer@example.test",
                        "subject": "Order received", "body": "Thanks for your order"}

    @patch("server.urlopen", return_value=FakeResponse())
    def test_sends_plain_text_and_returns_only_provider_reference(self, request):
        result = server.send_email(self.config, self.message)
        self.assertEqual({"accepted": True, "providerReference": "<message-123>", "retryAfter": None, "failureCode": None}, result)
        outbound = request.call_args.args[0]
        self.assertEqual("https://api.brevo.com/v3/smtp/email", outbound.full_url)
        self.assertEqual("xkeysib-test-secret", outbound.get_header("Api-key"))
        payload = json.loads(outbound.data)
        self.assertEqual("buyer@example.test", payload["to"][0]["email"])
        self.assertEqual("Thanks for your order", payload["textContent"])

    def test_rejects_non_email_channel_and_invalid_destination(self):
        with self.assertRaises(server.DeliveryError):
            server.send_email(self.config, {**self.message, "channel": "SMS"})
        with self.assertRaises(server.DeliveryError):
            server.send_email(self.config, {**self.message, "destination": "invalid"})


if __name__ == "__main__":
    unittest.main()
