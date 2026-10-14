import json
import base64
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import server


class FakeResponse:
    def __init__(self, body):
        self.body = body

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self, _limit):
        return self.body


class SwissPostTests(unittest.TestCase):
    def setUp(self):
        server._token_cache.clear()
        self.temp = tempfile.TemporaryDirectory()
        self.db = str(Path(self.temp.name) / "shipments.db")
        self.config = {"clientId": "client", "clientSecret": "a-long-test-secret",
                       "frankingLicense": "12345678", "senderName": "Artio AG",
                       "senderStreet": "Wankdorfallee 4", "senderPostalCode": "3030",
                       "senderCity": "Bern", "shippingPricesChf": {"ECO": "5.90", "PRI": "8.50"}}
        self.shipment = {"orderId": "order-123", "methodId": "swiss-post-pri", "destination": {
            "countryCode": "CH", "postalCode": "8000", "locality": "Zurich",
            "addressLine1": "Bahnhofstrasse 1", "recipientName": "Ada Lovelace"},
            "parcelWeightKg": 1.25, "currency": "CHF"}
        self.envelope = {"idempotencyKey": "12345678-1234-4234-8234-123456789abc"}
        self.db_patch = patch.object(server, "sqlite_connection", side_effect=self.sqlite_connection)
        self.db_patch.start()

    def tearDown(self):
        self.db_patch.stop()
        self.temp.cleanup()

    def sqlite_connection(self):
        connection = __import__("sqlite3").connect(self.db, timeout=5)
        connection.execute("CREATE TABLE IF NOT EXISTS shipments (idem TEXT PRIMARY KEY, request_hash TEXT NOT NULL, state TEXT NOT NULL, result TEXT)")
        return connection

    def test_quote_uses_merchant_configured_chf_rate(self):
        result = server.operation(self.config, "quote", self.shipment, self.envelope)
        self.assertEqual("8.50", result["amount"])
        self.assertEqual("CHF", result["currency"])

    def test_access_token_cache_expires_before_provider_deadline_and_refreshes(self):
        body = json.dumps({"access_token": "short-lived-token", "expires_in": 300}).encode()
        with patch.object(server.time, "monotonic", side_effect=[100.0, 369.0, 370.0, 370.0]), \
                patch.object(server, "urlopen", return_value=FakeResponse(body)) as request:
            first = server.access_token(self.config)
            cached = server.access_token(self.config)
            refreshed = server.access_token(self.config)

        self.assertEqual("short-lived-token", first)
        self.assertEqual(first, cached)
        self.assertEqual(first, refreshed)
        self.assertEqual(2, request.call_count)

    def test_access_token_without_valid_expiry_is_not_cached(self):
        body = json.dumps({"access_token": "uncached-token", "expires_in": "300"}).encode()
        with patch.object(server, "urlopen", return_value=FakeResponse(body)) as request:
            self.assertEqual("uncached-token", server.access_token(self.config))
            self.assertEqual("uncached-token", server.access_token(self.config))
        self.assertEqual(2, request.call_count)

    def test_shipment_maps_swiss_barcode_request_and_replays_completed_idempotency_key(self):
        expected = {"shipmentReference": "993590003400000002", "trackingReference": "993590003400000002",
                    "labelUrl": "data:application/pdf;base64,JVBERi0x"}
        with patch.object(server, "create_label", return_value=expected) as create:
            first = server.operation(self.config, "createShipment", self.shipment, self.envelope)
            second = server.operation(self.config, "createShipment", self.shipment, self.envelope)
        self.assertEqual(first, second)
        create.assert_called_once_with(self.config, self.shipment, self.envelope["idempotencyKey"])

    def test_label_call_uses_official_barcode_endpoint_and_bounded_pdf_response(self):
        response = {"item": {"identCode": "993590003400000002", "label": [base64.b64encode(b"%PDF-1.4").decode()]}}
        with patch.object(server, "access_token", return_value="access-token"), \
                patch.object(server, "urlopen", return_value=FakeResponse(json.dumps(response).encode())) as request:
            result = server.create_label(self.config, self.shipment, self.envelope["idempotencyKey"])
        outbound = request.call_args.args[0]
        self.assertEqual(server.BARCODE_URL, outbound.full_url)
        self.assertEqual("Bearer access-token", outbound.get_header("Authorization"))
        sent = json.loads(outbound.data)
        self.assertEqual("PRI", sent["item"]["attributes"]["przl"][0])
        self.assertEqual(1250, sent["item"]["attributes"]["weight"])
        self.assertEqual(self.envelope["idempotencyKey"], sent["sending"]["sendingID"])
        self.assertTrue(result["labelUrl"].startswith("data:application/pdf;base64,"))

    def test_uncertain_provider_outcome_is_not_retried_as_a_second_shipment(self):
        with patch.object(server, "create_label", side_effect=server.ProviderError(503, "uncertain")) as create:
            with self.assertRaises(server.ProviderError):
                server.operation(self.config, "createShipment", self.shipment, self.envelope)
            with self.assertRaisesRegex(server.ProviderError, "uncertain outcome"):
                server.operation(self.config, "createShipment", self.shipment, self.envelope)
        create.assert_called_once()

    def test_maps_official_domestic_services_and_disables_unimplemented_tracking(self):
        methods = server.operation(self.config, "methods", {"destination": {"countryCode": "CH"}, "currency": "CHF"}, self.envelope)
        self.assertEqual({"swiss-post-eco", "swiss-post-pri"}, {entry["methodId"] for entry in methods["methods"]})
        with patch.object(server, "access_token", return_value="test-token"):
            capabilities = server.operation(self.config, "capabilities", {}, self.envelope)
        self.assertFalse(capabilities["tracking"])
        with self.assertRaisesRegex(server.ProviderError, "tracking API"):
            server.operation(self.config, "track", {"trackingReference": "123"}, self.envelope)


if __name__ == "__main__":
    unittest.main()
