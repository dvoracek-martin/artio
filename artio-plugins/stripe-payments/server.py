"""Artio Stripe payment extension. Uses Stripe's REST API without SDK dependencies."""

import base64
import hashlib
import hmac
import json
import os
import re
import time
import uuid
from decimal import Decimal, InvalidOperation
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

MAX_BODY = 1_048_576
ZERO_DECIMAL = {"bif", "clp", "djf", "gnf", "jpy", "kmf", "krw", "mga", "pyg", "rwf", "ugx", "vnd", "vuv", "xaf", "xof", "xpf"}
THREE_DECIMAL = {"bhd", "jod", "kwd", "omr", "tnd"}


class ProviderError(Exception):
    def __init__(self, status, message="Payment provider request failed"):
        super().__init__(message)
        self.status = status


def minor_units(amount, currency):
    code = currency.lower()
    digits = 0 if code in ZERO_DECIMAL else 3 if code in THREE_DECIMAL else 2
    try:
        scaled = Decimal(str(amount)) * (10 ** digits)
        integral = scaled.to_integral_exact()
    except (InvalidOperation, TypeError, ValueError):
        raise ProviderError(400, "Invalid checkout amount")
    if integral <= 0 or scaled != integral:
        raise ProviderError(400, "Amount is not a positive valid minor-unit value")
    return int(integral)


def stripe_request(configuration, path, method="GET", form=None, idempotency_key=None):
    key = configuration.get("stripeSecretKey")
    if not isinstance(key, str) or not key.strip():
        raise ProviderError(503, "Stripe credentials are not configured")
    test_mode = configuration.get("testMode", True)
    if not isinstance(test_mode, bool) or (test_mode and not key.startswith("sk_test_")) or (not test_mode and not key.startswith("sk_live_")):
        raise ProviderError(400, "Stripe secret key does not match the selected test/live mode")
    base = "https://api.stripe.com"
    headers = {"Authorization": "Bearer " + key, "Accept": "application/json"}
    body = None
    if form is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded"
        body = urlencode(form, doseq=True).encode("utf-8")
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    request = Request(base + path, data=body, headers=headers, method=method)
    try:
        with urlopen(request, timeout=8) as response:
            content = response.read(MAX_BODY + 1)
            if len(content) > MAX_BODY:
                raise ProviderError(502, "Stripe response exceeded the configured limit")
            return json.loads(content or b"{}")
    except HTTPError as error:
        # Do not include Stripe's body or headers: they can contain customer data.
        status = 503 if error.code in (408, 409, 429, 500, 502, 503, 504) else 400
        raise ProviderError(status, "Stripe rejected the payment operation") from None
    except (URLError, TimeoutError, OSError, json.JSONDecodeError):
        raise ProviderError(503, "Stripe is temporarily unavailable") from None


def verify_stripe_signature(secret, header, body, now=None):
    if not secret or not isinstance(header, str):
        return False
    timestamp = None
    signatures = []
    for part in header.split(","):
        key, separator, value = part.partition("=")
        if not separator:
            continue
        if key == "t":
            timestamp = value
        elif key == "v1":
            signatures.append(value)
    if timestamp is None or not timestamp.isdecimal() or not signatures:
        return False
    timestamp_value = int(timestamp)
    current = int(time.time()) if now is None else int(now)
    if abs(current - timestamp_value) > 300:
        return False
    signed = timestamp.encode("ascii") + b"." + body
    expected = hmac.new(secret.encode("utf-8"), signed, hashlib.sha256).hexdigest()
    return any(hmac.compare_digest(expected, value) for value in signatures)


def operation(configuration, name, payload, envelope):
    if name == "capabilities":
        return {"hostedCheckout": True, "authorization": False, "capture": False,
                "cancellation": True, "refunds": True, "webhooks": True,
                "maximumCheckoutExpirySeconds": 86400}
    if name == "createCheckout":
        currency = payload.get("currency", "")
        if not re.fullmatch(r"[A-Za-z]{3}", currency):
            raise ProviderError(400, "Invalid currency")
        metadata = payload.get("metadata") or {}
        form = {
            "mode": "payment",
            "success_url": payload.get("successUrl"),
            "cancel_url": payload.get("cancelUrl"),
            "client_reference_id": payload.get("orderId"),
            "metadata[order_id]": payload.get("orderId"),
            "line_items[0][price_data][currency]": currency.lower(),
            "line_items[0][price_data][unit_amount]": str(minor_units(payload.get("amount"), currency)),
            "line_items[0][price_data][product_data][name]": "Artio order " + str(payload.get("orderId", ""))[:8],
            "line_items[0][quantity]": "1",
        }
        expires = metadata.get("expiresAt")
        if isinstance(expires, str):
            try:
                from datetime import datetime
                form["expires_at"] = str(int(datetime.fromisoformat(expires.replace("Z", "+00:00")).timestamp()))
            except ValueError:
                raise ProviderError(400, "Invalid checkout expiration")
        for key, value in metadata.items():
            if isinstance(value, (str, int, float, bool)):
                form["metadata[" + str(key) + "]"] = str(value)
        session = stripe_request(configuration, "/v1/checkout/sessions", "POST", form,
                                 envelope.get("idempotencyKey"))
        if not session.get("id") or not session.get("url"):
            raise ProviderError(502, "Stripe returned an incomplete checkout session")
        return {"providerReference": session["id"], "checkoutUrl": session["url"], "state": "PENDING"}
    if name == "cancel":
        reference = payload.get("providerReference")
        if not isinstance(reference, str) or not reference.startswith("cs_"):
            raise ProviderError(400, "Invalid checkout session reference")
        result = stripe_request(configuration, "/v1/checkout/sessions/" + reference + "/expire", "POST", {},
                                envelope.get("idempotencyKey"))
        return {"providerReference": reference, "state": "CANCELLED", "amount": None,
                "currency": None, "failureCode": result.get("status")}
    if name == "refund":
        reference = payload.get("providerReference")
        if not isinstance(reference, str) or not reference.startswith("cs_"):
            raise ProviderError(400, "Invalid checkout session reference")
        session = stripe_request(configuration, "/v1/checkout/sessions/" + reference)
        intent = session.get("payment_intent")
        if not isinstance(intent, str) or not intent.startswith("pi_"):
            raise ProviderError(409, "Checkout session has no refundable payment")
        refund = stripe_request(configuration, "/v1/refunds", "POST", {"payment_intent": intent},
                                envelope.get("idempotencyKey"))
        state = "REFUNDED" if refund.get("status") == "succeeded" else "PENDING"
        return {"providerReference": reference, "state": state, "amount": payload.get("amount"),
                "currency": payload.get("currency"), "failureCode": refund.get("failure_reason")}
    if name == "reconcile":
        reference = payload.get("providerReference")
        if not isinstance(reference, str) or not reference.startswith("cs_"):
            raise ProviderError(400, "Invalid checkout session reference")
        session = stripe_request(configuration, "/v1/checkout/sessions/" + reference)
        status = session.get("status")
        payment_status = session.get("payment_status")
        state = "CAPTURED" if status == "complete" and payment_status == "paid" else "EXPIRED" if status == "expired" else "PENDING"
        if state == "PENDING":
            return {"result": None}
        return {"result": {"providerReference": reference, "state": state,
                "amount": session.get("amount_total"), "currency": session.get("currency"), "failureCode": None}}
    if name == "verifyWebhook":
        try:
            body = base64.b64decode(payload.get("rawBodyBase64", ""), validate=True)
        except (ValueError, TypeError):
            raise ProviderError(400, "Invalid webhook payload")
        headers = payload.get("headers") or {}
        signature = headers.get("stripe-signature") or headers.get("provider-signature") or payload.get("signature")
        if not verify_stripe_signature(configuration.get("webhookSecret", ""), signature, body):
            raise ProviderError(400, "Invalid webhook signature")
        try:
            event = json.loads(body)
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise ProviderError(400, "Invalid webhook payload")
        event_type = event.get("type", "")
        obj = event.get("data", {}).get("object", {})
        state = "PENDING"
        if event_type == "checkout.session.completed" and obj.get("payment_status") == "paid":
            state = "CAPTURED"
        elif event_type == "checkout.session.expired":
            state = "EXPIRED"
        elif event_type in ("checkout.session.async_payment_failed",):
            state = "FAILED"
        return {"providerEventId": event.get("id"), "eventType": event_type,
                "providerReference": obj.get("id"), "state": state, "verified": True}
    if name == "capture":
        raise ProviderError(409, "Hosted Checkout does not support separate capture")
    raise ProviderError(400, "Unsupported payment operation")


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            return self.respond(200, {"status": "healthy"})
        if self.path == "/diagnostics":
            return self.respond(200, {"status": "ready", "provider": "stripe", "secrets": "redacted"})
        self.respond(404, {"error": "not_found"})

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
            if envelope.get("protocolVersion") != 1 or envelope.get("extensionPoint") != "payment.provider.v1":
                return self.respond(400, {"error": "unsupported_contract"})
            idempotency_key = str(envelope.get("idempotencyKey", ""))
            try:
                if str(uuid.UUID(idempotency_key)) != idempotency_key.lower():
                    raise ValueError()
            except ValueError:
                return self.respond(400, {"error": "invalid_idempotency_key"})
            result = operation(envelope.get("configuration") or {}, envelope.get("operation"),
                               envelope.get("payload") or {}, envelope)
            self.respond(200, result)
        except ProviderError as error:
            self.respond(error.status, {"error": str(error)})
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
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
