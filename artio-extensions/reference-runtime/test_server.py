import os
import tempfile
import unittest
from unittest.mock import patch

import server


class DurableEventAcknowledgementTests(unittest.TestCase):
    def test_delivery_is_idempotent_and_detects_identity_reuse(self):
        with tempfile.TemporaryDirectory() as directory:
            state = os.path.join(directory, "deliveries.json")
            envelope = {"idempotencyKey": "d92bd7a8-8a38-4c7b-bd5c-e8e1f0f10347",
                        "payload": {"eventId": "a", "type": "order.created"}}
            with patch.dict(os.environ, {"ARTIO_REFERENCE_STATE_PATH": state}):
                status, first = server.acknowledge_delivery(envelope)
                status_duplicate, duplicate = server.acknowledge_delivery(envelope)
                changed = {**envelope, "payload": {"eventId": "b", "type": "order.created"}}
                status_conflict, conflict = server.acknowledge_delivery(changed)

            self.assertEqual(status, 200)
            self.assertEqual(first, {"accepted": True, "deliveryId": envelope["idempotencyKey"], "duplicate": False})
            self.assertEqual(status_duplicate, 200)
            self.assertEqual(duplicate["duplicate"], True)
            self.assertEqual(status_conflict, 409)
            self.assertEqual(conflict["error"], "delivery_identity_conflict")


if __name__ == "__main__":
    unittest.main()
