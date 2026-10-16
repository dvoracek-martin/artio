"""Artio PayPal payment extension implementing payment.provider.v1."""

import base64
import hmac
import json
import os
import re
from decimal import Decimal, InvalidOperation
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

MAX_BODY = 1_048_576
ZERO_DECIMAL = {"bif", "clp", "djf", "gnf", "jpy", "kmf", "krw", "mga", "pyg", "rwf", "ugx", "vnd", "vuv", "xaf", "xof", "xpf"}
THREE_DECIMAL = {"bhd", "jod", "kwd", "omr", "tnd"}


class ProviderError(Exception):
    def __init__(self, status, message="PayPal request failed"):
        super().__init__(message)
        self.status = status


def amount_text(value, currency):
    code = str(currency).upper()
    if not re.fullmatch(r"[A-Z]{3}", code):
        raise ProviderError(400, "Invalid currency")
    normalized = code.lower()
    digits = 0 if normalized in ZERO_DECIMAL else 3 if normalized in THREE_DECIMAL else 2
    try:
        amount = Decimal(str(value))
        rounded = amount.quantize(Decimal(1).scaleb(-digits))
    except (InvalidOperation, TypeError, ValueError):
        raise ProviderError(400, "Invalid amount")
    if not amount.is_finite() or amount <= 0 or rounded != amount:
        raise ProviderError(400, "Amount has unsupported precision")
    return format(rounded, f".{digits}f")


def api_root(configuration):
    sandbox = configuration.get("sandboxMode", True)
    if not isinstance(sandbox, bool):
        raise ProviderError(400, "Invalid PayPal environment setting")
    return "https://api-m.sandbox.paypal.com" if sandbox else "https://api-m.paypal.com"


def credentials(configuration):
    client_id, client_secret = configuration.get("clientId"), configuration.get("clientSecret")
    if not isinstance(client_id, str) or not client_id.strip() or not isinstance(client_secret, str) or not client_secret.strip():
        raise ProviderError(503, "PayPal credentials are not configured")
    if len(client_id) > 512 or len(client_secret) > 512:
        raise ProviderError(400, "PayPal credentials are invalid")
    return client_id, client_secret


def request_json(url, method="GET", headers=None, body=None):
    request = Request(url, data=body, headers=headers or {}, method=method)
    try:
        with urlopen(request, timeout=8) as response:
            content = response.read(MAX_BODY + 1)
            if len(content) > MAX_BODY:
                raise ProviderError(502, "PayPal response exceeded the configured limit")
            return json.loads(content or b"{}")
    except HTTPError as error:
        status = 503 if error.code in (408, 409, 429, 500, 502, 503, 504) else 400
        raise ProviderError(status) from None
    except (URLError, TimeoutError, OSError, json.JSONDecodeError):
        raise ProviderError(503, "PayPal is temporarily unavailable") from None


def access_token(configuration):
    client_id, client_secret = credentials(configuration)
    basic = base64.b64encode((client_id + ":" + client_secret).encode()).decode("ascii")
    result = request_json(api_root(configuration) + "/v1/oauth2/token", "POST",
                          {"Authorization": "Basic " + basic, "Accept": "application/json",
                           "Content-Type": "application/x-www-form-urlencoded"},
                          urlencode({"grant_type": "client_credentials"}).encode())
    token = result.get("access_token")
    if not isinstance(token, str) or not token:
        raise ProviderError(502, "PayPal returned an invalid access token")
    return token


def paypal_request(configuration, path, method="GET", data=None, idempotency_key=None):
    token = access_token(configuration)
    headers = {"Authorization": "Bearer " + token, "Accept": "application/json",
               "Content-Type": "application/json", "Prefer": "return=representation"}
    if idempotency_key:
        headers["PayPal-Request-Id"] = idempotency_key[:108]
    body = json.dumps(data, separators=(",", ":")).encode() if data is not None else None
    return request_json(api_root(configuration) + path, method, headers, body)


def operation(configuration, name, payload, envelope):
    if name == "capabilities":
        return {"hostedCheckout": True, "authorization": False, "capture": True,
                "cancellation": False, "refunds": True, "webhooks": True,
                "maximumCheckoutExpirySeconds": None}
    if name == "listMethods":
        countries = configuration.get("supportedCountries", [])
        if not isinstance(countries, list) or len(countries) > 250 or any(
                not isinstance(country, str) or not re.fullmatch(r"[A-Z]{2}", country) for country in countries):
            raise ProviderError(400, "Invalid supported country configuration")
        return {"methods": [{"methodId": "paypal", "displayName": "PayPal", "countries": countries,
                "currencies": ["AUD", "BRL", "CAD", "CHF", "CZK", "DKK", "EUR", "GBP", "HKD", "HUF",
                               "ILS", "JPY", "MXN", "MYR", "NOK", "NZD", "PHP", "PLN", "SEK", "SGD", "THB", "TWD", "USD"],
                "hostedCheckout": True}]}
    if name == "createCheckout":
        if payload.get("paymentMethodId") != "paypal":
            raise ProviderError(400, "Selected payment method is not enabled for this provider")
        amount = amount_text(payload.get("amount"), payload.get("currency"))
        request = {"intent": "CAPTURE", "purchase_units": [{
            "reference_id": str(payload.get("orderId", "")), "custom_id": str(payload.get("orderId", "")),
            "amount": {"currency_code": str(payload["currency"]).upper(), "value": amount}}],
            "application_context": {"return_url": payload.get("successUrl"), "cancel_url": payload.get("cancelUrl"),
                                    "user_action": "PAY_NOW"}}
        result = paypal_request(configuration, "/v2/checkout/orders", "POST", request, envelope.get("idempotencyKey"))
        approve = next((link.get("href") for link in result.get("links", []) if link.get("rel") in ("approve", "payer-action")), None)
        if not result.get("id") or not isinstance(approve, str) or not approve.startswith("https://"):
            raise ProviderError(502, "PayPal returned an incomplete checkout")
        return {"providerReference": result["id"], "checkoutUrl": approve, "state": "PENDING"}
    if name == "capture":
        reference = str(payload.get("providerReference", ""))
        if not re.fullmatch(r"[A-Z0-9-]{8,64}", reference):
            raise ProviderError(400, "Invalid PayPal order reference")
        result = paypal_request(configuration, "/v2/checkout/orders/" + reference + "/capture", "POST", {}, envelope.get("idempotencyKey"))
        purchase_units = result.get("purchase_units") or []
        captures = (purchase_units[0].get("payments") or {}).get("captures") or [] if purchase_units else []
        capture = captures[0] if captures else {}
        amount = capture.get("amount") or {}
        state = "CAPTURED" if capture.get("status") == "COMPLETED" else "PENDING"
        return {"providerReference": reference, "transactionReference": capture.get("id"), "state": state, "amount": amount.get("value"),
                "currency": amount.get("currency_code"), "failureCode": None}
    if name == "cancel":
        raise ProviderError(409, "PayPal orders cannot be cancelled through this provider contract")
    if name == "refund":
        reference = str(payload.get("providerReference", ""))
        if not re.fullmatch(r"[A-Z0-9-]{8,64}", reference):
            raise ProviderError(400, "Invalid PayPal order reference")
        details = paypal_request(configuration, "/v2/checkout/orders/" + reference)
        units = details.get("purchase_units") or []
        captures = (units[0].get("payments") or {}).get("captures") or [] if units else []
        capture_id = captures[0].get("id") if captures else None
        if not isinstance(capture_id, str) or not re.fullmatch(r"[A-Z0-9-]{8,64}", capture_id):
            raise ProviderError(409, "PayPal order has no refundable capture")
        amount = amount_text(payload.get("amount"), payload.get("currency"))
        result = paypal_request(configuration, "/v2/payments/captures/" + capture_id + "/refund", "POST",
                                {"amount": {"value": amount, "currency_code": str(payload.get("currency")).upper()}},
                                envelope.get("idempotencyKey"))
        state = "REFUNDED" if result.get("status") == "COMPLETED" else "PENDING"
        return {"providerReference": result.get("id"), "state": state, "amount": amount,
                "currency": str(payload.get("currency")).upper(), "failureCode": None}
    if name == "reconcile":
        reference = str(payload.get("providerReference", ""))
        if not re.fullmatch(r"[A-Z0-9-]{8,64}", reference):
            raise ProviderError(400, "Invalid PayPal order reference")
        result = paypal_request(configuration, "/v2/checkout/orders/" + reference)
        if result.get("status") != "COMPLETED":
            return {"result": None}
        units = result.get("purchase_units") or []
        captures = (units[0].get("payments") or {}).get("captures") or [] if units else []
        capture = captures[0] if captures else {}
        amount = capture.get("amount") or {}
        currency = amount.get("currency_code")
        normalized_amount = amount_text(amount.get("value"), currency)
        return {"result": {"providerReference": reference, "transactionReference": capture.get("id"), "state": "CAPTURED",
                            "amount": normalized_amount, "currency": currency, "failureCode": None}}
    if name == "verifyWebhook":
        raw = payload.get("rawBodyBase64", "")
        try:
            body = base64.b64decode(raw, validate=True)
            event = json.loads(body)
        except (ValueError, TypeError, json.JSONDecodeError):
            raise ProviderError(400, "Invalid webhook payload")
        headers = payload.get("headers") or {}
        required = {"transmission_id": headers.get("paypal-transmission-id"),
                    "transmission_time": headers.get("paypal-transmission-time"),
                    "transmission_sig": headers.get("paypal-transmission-sig"),
                    "cert_url": headers.get("paypal-cert-url"), "auth_algo": headers.get("paypal-auth-algo")}
        webhook_id = configuration.get("webhookId")
        if not isinstance(webhook_id, str) or not webhook_id.startswith("WH-") or not all(required.values()):
            raise ProviderError(400, "PayPal webhook verification configuration is incomplete")
        verification = paypal_request(configuration, "/v1/notifications/verify-webhook-signature", "POST",
                                      {**required, "webhook_id": webhook_id, "webhook_event": event})
        if verification.get("verification_status") != "SUCCESS":
            raise ProviderError(400, "Invalid PayPal webhook signature")
        event_type = event.get("event_type", "")
        resource = event.get("resource") or {}
        related = (resource.get("supplementary_data") or {}).get("related_ids") or {}
        order_id = related.get("order_id") or resource.get("id")
        state = "PENDING"
        if event_type == "PAYMENT.CAPTURE.COMPLETED":
            state = "CAPTURED"
        elif event_type in ("PAYMENT.CAPTURE.REVERSED", "CHECKOUT.PAYMENT-APPROVAL.REVERSED"):
            state = "REVERSED"
        elif event_type in ("PAYMENT.CAPTURE.DENIED", "PAYMENT.CAPTURE.REFUNDED"):
            state = "FAILED" if event_type.endswith("DENIED") else "REFUNDED"
        amount = resource.get("amount") or {}
        amount_value = amount_text(amount.get("value"), amount.get("currency_code")) if state in ("CAPTURED", "REVERSED") else None
        return {"providerEventId": event.get("id"), "eventType": event_type,
                "providerReference": order_id, "transactionReference": related.get("capture_id") or resource.get("id"),
                "state": state, "amount": amount_value,
                "currency": amount.get("currency_code"), "verified": True}
    raise ProviderError(400, "Unsupported payment operation")


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path in ("/health", "/diagnostics"):
            return self.respond(200, {"status": "healthy" if self.path == "/health" else "ready",
                                      "provider": "paypal", "secrets": "redacted"})
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
            key = str(envelope.get("idempotencyKey", ""))
            if len(key) > 128 or not re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", key):
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
    ThreadingHTTPServer(("0.0.0.0", int(os.environ.get("PORT", "8080"))), Handler).serve_forever()
