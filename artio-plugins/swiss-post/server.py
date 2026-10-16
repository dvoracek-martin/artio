"""Swiss Post Digital Commerce Barcode shipping extension for Artio."""

import base64
from contextlib import contextmanager
import hashlib
import hmac
import json
import os
import re
import sqlite3
import threading
import time
from decimal import Decimal, InvalidOperation
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

MAX_BODY = 1_048_576
MAX_PROVIDER_BODY = 2_000_000
TOKEN_URL = "https://api.post.ch/OAuth/token"
BARCODE_URL = "https://dcapi.apis.post.ch/barcode/v1/generateAddressLabel"
_token_cache = {}
_token_lock = threading.Lock()


class ProviderError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def post_request(configuration, url, method="GET", body=None, content_type="application/json"):
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = content_type
    request = Request(url, data=body, headers=headers, method=method)
    try:
        with urlopen(request, timeout=8) as response:
            payload = response.read(MAX_PROVIDER_BODY + 1)
            if len(payload) > MAX_PROVIDER_BODY:
                raise ProviderError(502, "Swiss Post response exceeded the size limit")
            return json.loads(payload or b"{}")
    except HTTPError as error:
        status = 503 if error.code in (408, 409, 429, 500, 502, 503, 504) else 400
        raise ProviderError(status, "Swiss Post rejected the request") from None
    except (URLError, TimeoutError, OSError, json.JSONDecodeError):
        raise ProviderError(503, "Swiss Post is temporarily unavailable") from None


def access_token(configuration):
    client_id = configuration.get("clientId")
    secret = configuration.get("clientSecret")
    if not isinstance(client_id, str) or not client_id.strip() or not isinstance(secret, str) or not secret.strip():
        raise ProviderError(503, "Swiss Post credentials are not configured")
    cache_key = hashlib.sha256((client_id + "\0" + secret).encode()).hexdigest()
    with _token_lock:
        cached = _token_cache.get(cache_key)
        if cached and cached[1] > time.monotonic():
            return cached[0]
        form = urlencode({"grant_type": "client_credentials", "client_id": client_id,
                          "client_secret": secret, "scope": "DCAPI_BARCODE_READ"}).encode()
        response = post_request(configuration, TOKEN_URL, "POST", form, "application/x-www-form-urlencoded")
        token = response.get("access_token")
        if not isinstance(token, str) or not token or len(token) > 16_384:
            raise ProviderError(502, "Swiss Post returned an invalid access token")
        expires_in = response.get("expires_in")
        if type(expires_in) is int and 0 < expires_in <= 86_400:
            # Refresh 30 seconds early to avoid using a token that expires in-flight.
            cache_seconds = expires_in - 30
            if cache_seconds > 0:
                _token_cache[cache_key] = (token, time.monotonic() + cache_seconds)
            else:
                _token_cache.pop(cache_key, None)
        else:
            # The token can still be used for this request, but must not live forever in cache.
            _token_cache.pop(cache_key, None)
        return token


def barcode_request(configuration, payload):
    token = access_token(configuration)
    body = json.dumps(payload, separators=(",", ":")).encode()
    request = Request(BARCODE_URL, data=body, headers={"Authorization": "Bearer " + token,
                    "Content-Type": "application/json", "Accept": "application/json"}, method="POST")
    try:
        with urlopen(request, timeout=15) as response:
            content = response.read(MAX_PROVIDER_BODY + 1)
            if len(content) > MAX_PROVIDER_BODY:
                raise ProviderError(502, "Swiss Post label response exceeded the size limit")
            result = json.loads(content or b"{}")
    except HTTPError as error:
        status = 503 if error.code in (408, 409, 429, 500, 502, 503, 504) else 400
        raise ProviderError(status, "Swiss Post rejected label generation") from None
    except (URLError, TimeoutError, OSError, json.JSONDecodeError):
        raise ProviderError(503, "Swiss Post label generation outcome is unavailable") from None
    item = result.get("item") or {}
    labels = item.get("label") or []
    if not isinstance(item.get("identCode"), str) or not labels or not isinstance(labels[0], str):
        raise ProviderError(502, "Swiss Post returned an incomplete label")
    try:
        label = base64.b64decode(labels[0], validate=True)
    except (ValueError, TypeError):
        raise ProviderError(502, "Swiss Post returned an invalid label") from None
    if not label or len(label) > 1_500_000:
        raise ProviderError(502, "Swiss Post label exceeded the supported size")
    return {"shipmentReference": item["identCode"], "trackingReference": item["identCode"],
            "labelUrl": "data:application/pdf;base64," + base64.b64encode(label).decode("ascii")}


def sqlite_connection():
    connection = sqlite3.connect(os.environ.get("ARTIO_SHIPMENT_DB", "/data/state/shipment-idempotency.db"), timeout=5)
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("CREATE TABLE IF NOT EXISTS shipments (idem TEXT PRIMARY KEY, request_hash TEXT NOT NULL, state TEXT NOT NULL, result TEXT)")
    return connection


@contextmanager
def database():
    connection = sqlite_connection()
    try:
        with connection:
            yield connection
    finally:
        connection.close()


def shipment_idempotent(configuration, payload, envelope):
    key = envelope.get("idempotencyKey")
    if not isinstance(key, str) or not re.fullmatch(r"[0-9a-fA-F-]{36}", key):
        raise ProviderError(400, "Shipment idempotency key must be a UUID")
    request_hash = hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    connection = sqlite_connection()
    try:
        connection.execute("BEGIN IMMEDIATE")
        row = connection.execute("SELECT request_hash,state,result FROM shipments WHERE idem=?", (key,)).fetchone()
        if row:
            connection.commit()
            if row[0] != request_hash:
                raise ProviderError(409, "Shipment idempotency key was reused with different details")
            if row[1] == "complete":
                return json.loads(row[2])
            raise ProviderError(409, "A previous shipment request has an uncertain outcome; do not submit it again")
        connection.execute("INSERT INTO shipments(idem,request_hash,state) VALUES(?,?, 'pending')", (key, request_hash))
        connection.commit()
    finally:
        connection.close()

    try:
        result = create_label(configuration, payload, key)
    except ProviderError as error:
        # Provider 4xx responses other than transient/conflict statuses are documented rejections.
        if error.status == 400:
            with database() as connection:
                connection.execute("DELETE FROM shipments WHERE idem=? AND request_hash=?", (key, request_hash))
        raise
    except Exception:
        # Persist pending state after an ambiguous failure so a retry cannot create a duplicate label.
        raise ProviderError(503, "Shipment creation outcome is uncertain; do not submit it again") from None
    with database() as connection:
        connection.execute("UPDATE shipments SET state='complete',result=? WHERE idem=? AND request_hash=?",
                           (json.dumps(result, separators=(",", ":")), key, request_hash))
    return result


def create_label(configuration, payload, idempotency_key):
    if payload.get("methodId") not in ("swiss-post-eco", "swiss-post-pri"):
        raise ProviderError(400, "Unsupported Swiss Post shipping service")
    destination = payload.get("destination") or {}
    if destination.get("countryCode") != "CH":
        raise ProviderError(400, "Swiss Post reference plugin currently supports domestic CH labels only")
    weight = payload.get("parcelWeightKg")
    try:
        grams = int((Decimal(str(weight)) * 1000).to_integral_exact())
    except (InvalidOperation, TypeError, ValueError):
        raise ProviderError(400, "Parcel weight in kilograms is required") from None
    if not 1 <= grams <= 99_999:
        raise ProviderError(400, "Parcel weight must be between 1 and 99999 grams")
    postal = str(destination.get("postalCode") or "")
    city = str(destination.get("locality") or "")
    address1 = str(destination.get("addressLine1") or "")
    if not re.fullmatch(r"\d{4,6}", postal) or not city or not address1:
        raise ProviderError(400, "A valid Swiss destination address is required")
    recipient = str(destination.get("recipientName") or "")[:35]
    if not recipient:
        raise ProviderError(400, "Recipient name is required")
    license_id = str(configuration.get("frankingLicense") or "")
    if not (re.fullmatch(r"[A-Za-z0-9]{4}", license_id) or re.fullmatch(r"\d{6}|\d{8}", license_id)):
        raise ProviderError(400, "Swiss Post franking licence format is invalid")
    service = "ECO" if payload["methodId"] == "swiss-post-eco" else "PRI"
    request = {
        "language": "EN",
        "frankingLicense": license_id.zfill(8),
        "customer": {"name1": configuration.get("senderName"), "street": configuration.get("senderStreet"),
                     "zip": str(configuration.get("senderPostalCode")), "city": configuration.get("senderCity"), "country": "CH"},
        "labelDefinition": {"labelLayout": configuration.get("labelLayout", "A6"),
                            "printAddresses": "RECIPIENT_AND_CUSTOMER", "imageFileType": "PDF",
                            "imageResolution": 300, "printPreview": bool(configuration.get("printPreview", True))},
        "item": {"itemID": str(payload.get("orderId") or idempotency_key)[:200],
                 "recipient": {"name1": recipient, "street": address1[:35], "zip": postal,
                               "city": city[:35], "country": "CH"},
                 "attributes": {"przl": [service], "weight": grams}},
        "sending": {"sendingID": idempotency_key}
    }
    return barcode_request(configuration, request)


def operation(configuration, name, payload, envelope):
    if name == "capabilities":
        # Token acquisition verifies that the registered API client credentials are accepted.
        access_token(configuration)
        return {"quotes": True, "shipmentCreation": True, "labels": True, "tracking": False, "countries": ["CH"]}
    if name in ("methods", "listMethods"):
        if payload.get("destination", {}).get("countryCode") != "CH" or payload.get("currency") != "CHF":
            return {"methods": []}
        return {"methods": [
            {"methodId": "swiss-post-eco", "name": "Swiss Post PostPac Economy", "estimatedDelivery": "Merchant configured", "metadata": {"serviceCode": "ECO"}},
            {"methodId": "swiss-post-pri", "name": "Swiss Post PostPac Priority", "estimatedDelivery": "Merchant configured", "metadata": {"serviceCode": "PRI"}}
        ]}
    if name == "quote":
        method = payload.get("methodId")
        prices = configuration.get("shippingPricesChf") or {}
        code = "ECO" if method == "swiss-post-eco" else "PRI" if method == "swiss-post-pri" else None
        if payload.get("destination", {}).get("countryCode") != "CH" or payload.get("currency") != "CHF" or code is None:
            raise ProviderError(400, "Unsupported Swiss Post quote destination, currency or method")
        try:
            amount = Decimal(str(prices[code])).quantize(Decimal("0.01"))
            if amount < 0 or amount > Decimal("10000"):
                raise InvalidOperation()
        except (KeyError, InvalidOperation, ValueError):
            raise ProviderError(400, "Configure a valid merchant CHF rate for the selected service") from None
        return {"methodId": method, "amount": str(amount), "currency": "CHF", "estimatedDelivery": "Merchant configured"}
    if name == "createShipment":
        return shipment_idempotent(configuration, payload, envelope)
    if name == "track":
        raise ProviderError(501, "Swiss Post tracking requires a separately entitled tracking API; it is not configured")
    raise ProviderError(400, "Unsupported shipping operation")


class Handler(BaseHTTPRequestHandler):
    def respond(self, status, body):
        content = json.dumps(body, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def do_GET(self):
        if self.path == "/health":
            return self.respond(200, {"status": "healthy"})
        if self.path == "/diagnostics":
            return self.respond(200, {"status": "ready", "provider": "swiss-post", "credentials": "redacted", "tracking": "not-configured"})
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
            if envelope.get("protocolVersion") != 1 or envelope.get("extensionPoint") != "shipping.provider.v1":
                return self.respond(400, {"error": "unsupported_contract"})
            result = operation(envelope.get("configuration") or {}, envelope.get("operation", ""),
                               envelope.get("payload") or {}, envelope)
            content = json.dumps({"protocolVersion": 1, "result": result}, separators=(",", ":")).encode()
            if len(content) > MAX_BODY:
                return self.respond(502, {"error": "response_too_large"})
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            self.wfile.write(content)
        except ProviderError as error:
            self.respond(error.status, {"error": str(error)})
        except (ValueError, TypeError, KeyError):
            self.respond(400, {"error": "invalid_request"})

    def log_message(self, format, *args):
        return


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", int(os.environ.get("PORT", "8080"))), Handler).serve_forever()
