import json
import hashlib
import os
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

DELIVERY_LOCK = threading.RLock()


def acknowledge_delivery(envelope):
    delivery_id = envelope["idempotencyKey"]
    payload = json.dumps(envelope["payload"], sort_keys=True, separators=(",", ":"))
    fingerprint = hashlib.sha256(payload.encode()).hexdigest()
    state = Path(os.environ.get("ARTIO_REFERENCE_STATE_PATH", "/var/lib/artio-reference/event-deliveries.json"))
    state.parent.mkdir(parents=True, exist_ok=True)
    with DELIVERY_LOCK:
        records = json.loads(state.read_text()) if state.exists() else {}
        existing = records.get(delivery_id)
        if existing is not None and existing != fingerprint:
            return 409, {"error": "delivery_identity_conflict"}
        duplicate = existing is not None
        if not duplicate:
            records[delivery_id] = fingerprint
            descriptor, temporary = tempfile.mkstemp(prefix=".deliveries-", dir=state.parent)
            try:
                with os.fdopen(descriptor, "w") as output:
                    json.dump(records, output, sort_keys=True, separators=(",", ":"))
                    output.flush()
                    os.fsync(output.fileno())
                os.replace(temporary, state)
            finally:
                if os.path.exists(temporary):
                    os.unlink(temporary)
        return 200, {"accepted": True, "deliveryId": delivery_id, "duplicate": duplicate}


class ExtensionHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/health":
            self.send_error(404)
            return
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"healthy")

    def do_POST(self):
        if self.path != "/v1/invoke":
            self.send_error(404)
            return
        if self.headers.get("Authorization") != "Bearer " + os.environ["ARTIO_EXTENSION_TOKEN"]:
            self.send_error(401)
            return
        if self.headers.get("X-Artio-Extension-Api") != "1":
            self.send_error(400)
            return
        payload = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        if payload["operation"] == "slow":
            time.sleep(1)
        if payload["extensionPoint"] == "events.subscriber.v1" and payload["operation"] == "deliver":
            status, response = acknowledge_delivery(payload)
        else:
            status = 200
            response = {"runtimeVersion": os.environ["ARTIO_REFERENCE_VERSION"], "operation": payload["operation"]}
        body = json.dumps(response).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass  # The timeout fixture deliberately lets the caller abandon its response.

    def log_message(self, *_):
        return


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8080), ExtensionHandler).serve_forever()
