import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


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
        body = json.dumps({"runtimeVersion": os.environ["ARTIO_REFERENCE_VERSION"], "operation": payload["operation"]}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass  # The timeout fixture deliberately lets the caller abandon its response.

    def log_message(self, *_):
        return


ThreadingHTTPServer(("0.0.0.0", 8080), ExtensionHandler).serve_forever()
