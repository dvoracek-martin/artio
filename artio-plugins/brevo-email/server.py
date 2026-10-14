"""Artio Brevo transactional email extension (notification.channel.v1)."""

import hmac
import json
import os
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

MAX_BODY = 1_048_576
EMAIL = re.compile(r"^[^\s@<>]{1,128}@[^\s@<>]{1,189}$")


class DeliveryError(Exception):
    def __init__(self, status, retry_after=None):
        super().__init__(status)
        self.status = status
        self.retry_after = retry_after


def send_email(configuration, message):
    key = configuration.get("apiKey")
    sender_email = configuration.get("senderEmail")
    sender_name = configuration.get("senderName")
    destination = message.get("destination")
    subject = message.get("subject")
    body_text = message.get("body")
    if not isinstance(key, str) or not key.startswith("xkeysib-"):
        raise DeliveryError(503)
    if not isinstance(sender_email, str) or not EMAIL.fullmatch(sender_email):
        raise DeliveryError(503)
    if not isinstance(destination, str) or not EMAIL.fullmatch(destination):
        raise DeliveryError(400)
    if not isinstance(subject, str) or not subject.strip() or len(subject) > 998:
        raise DeliveryError(400)
    if not isinstance(body_text, str) or not body_text.strip() or len(body_text.encode("utf-8")) > 250_000:
        raise DeliveryError(400)
    if message.get("channel", "EMAIL").upper() != "EMAIL":
        raise DeliveryError(400)

    sender = {"email": sender_email}
    if isinstance(sender_name, str) and sender_name.strip():
        sender["name"] = sender_name.strip()[:100]
    payload = json.dumps({
        "sender": sender,
        "to": [{"email": destination}],
        "subject": subject,
        "textContent": body_text,
        "headers": {"X-Artio-Message-ID": str(message.get("idempotencyKey", ""))[:128]},
    }, separators=(",", ":")).encode("utf-8")
    request = Request("https://api.brevo.com/v3/smtp/email", data=payload,
                      headers={"api-key": key, "Accept": "application/json",
                               "Content-Type": "application/json"}, method="POST")
    try:
        with urlopen(request, timeout=8) as response:
            response_body = response.read(MAX_BODY + 1)
            if len(response_body) > MAX_BODY:
                raise DeliveryError(502)
            result = json.loads(response_body or b"{}")
            reference = result.get("messageId")
            if not isinstance(reference, str) or not reference:
                raise DeliveryError(502)
            return {"accepted": True, "providerReference": reference,
                    "retryAfter": None, "failureCode": None}
    except HTTPError as error:
        retry_after = error.headers.get("Retry-After") if error.code in (429, 500, 502, 503, 504) else None
        raise DeliveryError(503 if error.code >= 500 or error.code == 429 else 400, retry_after) from None
    except (URLError, TimeoutError, OSError, json.JSONDecodeError):
        raise DeliveryError(503) from None


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path not in ("/health", "/diagnostics"):
            return self.respond(404, {"error": "not_found"})
        self.respond(200, {"status": "healthy" if self.path == "/health" else "ready",
                           "provider": "brevo", "secrets": "redacted"})

    def do_POST(self):
        if self.path != "/v1/invoke":
            return self.respond(404, {"error": "not_found"})
        token = os.environ.get("ARTIO_EXTENSION_TOKEN", "")
        if not token or not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + token):
            return self.respond(401, {"error": "unauthorized"})
        if self.headers.get("X-Artio-Extension-Api") != "1":
            return self.respond(400, {"error": "unsupported_protocol"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 2 or length > MAX_BODY:
                return self.respond(413, {"error": "request_too_large"})
            envelope = json.loads(self.rfile.read(length))
            if envelope.get("protocolVersion") != 1 or envelope.get("extensionPoint") != "notification.channel.v1" or envelope.get("operation") != "deliver":
                return self.respond(400, {"error": "unsupported_contract"})
            result = send_email(envelope.get("configuration") or {}, envelope.get("payload") or {})
            self.respond(200, result)
        except DeliveryError as error:
            self.respond(error.status, {"accepted": False, "providerReference": None,
                                        "retryAfter": error.retry_after, "failureCode": "provider_rejected"})
        except (ValueError, TypeError, json.JSONDecodeError):
            self.respond(400, {"error": "invalid_request"})
        except Exception:
            self.respond(500, {"error": "internal_error"})

    def respond(self, status, value):
        body = json.dumps(value, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        return


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", int(os.environ.get("PORT", "8080"))), Handler).serve_forever()
