"""Artio PostFinance Checkout payment extension using the official Python SDK."""

import base64
import hmac
import json
import os
import re
import uuid
from decimal import Decimal, InvalidOperation
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BODY = 1_048_576
DEFAULT_CURRENCIES = ["CHF"]
ZERO_DECIMAL = {"BIF", "CLP", "DJF", "GNF", "JPY", "KMF", "KRW", "MGA", "PYG", "RWF", "UGX", "VND", "VUV", "XAF", "XOF", "XPF"}
THREE_DECIMAL = {"BHD", "JOD", "KWD", "OMR", "TND"}


def currency_digits(currency):
    return 0 if currency in ZERO_DECIMAL else 3 if currency in THREE_DECIMAL else 2


class ProviderError(Exception):
    def __init__(self, status, message="PostFinance Checkout request failed"):
        super().__init__(message)
        self.status = status


def supported_currencies(configuration):
    currencies = configuration.get("supportedCurrencies", DEFAULT_CURRENCIES)
    if (not isinstance(currencies, list) or not currencies or len(currencies) > 40
            or any(not isinstance(code, str) or not re.fullmatch(r"[A-Z]{3}", code) for code in currencies)
            or len(set(currencies)) != len(currencies)):
        raise ProviderError(400, "Invalid supported currency configuration")
    return currencies


def amount_decimal(value, currency):
    if not isinstance(currency, str) or not re.fullmatch(r"[A-Z]{3}", currency):
        raise ProviderError(400, "Invalid currency")
    try:
        amount = Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError):
        raise ProviderError(400, "Invalid payment amount") from None
    digits = currency_digits(currency)
    if not amount.is_finite() or amount <= 0 or amount.as_tuple().exponent < -digits:
        raise ProviderError(400, "Amount has invalid currency precision")
    return amount


def major_amount(value, currency):
    if isinstance(value, bool) or not isinstance(value, (int, float, Decimal)):
        return None
    try:
        amount = Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError):
        return None
    if not isinstance(currency, str) or not re.fullmatch(r"[A-Z]{3}", currency):
        return None
    digits = currency_digits(currency)
    if not amount.is_finite() or amount <= 0 or amount.as_tuple().exponent < -digits:
        return None
    return format(amount, f".{digits}f")


def api_services(configuration):
    user_id = configuration.get("applicationUserId")
    auth_key = configuration.get("authenticationKey")
    space = configuration.get("spaceId")
    if (not isinstance(user_id, str) or not user_id.isdecimal() or not 1 <= len(user_id) <= 20
            or not isinstance(auth_key, str) or not auth_key.strip()
            or not isinstance(space, (str, int)) or not str(space).isdecimal() or not 1 <= len(str(space)) <= 20):
        raise ProviderError(503, "PostFinance Checkout credentials are not configured")
    try:
        from postfinancecheckout.configuration import Configuration
        from postfinancecheckout.service.transactions_service import TransactionsService
        from postfinancecheckout.service.refunds_service import RefundsService
        from postfinancecheckout.service.webhook_encryption_keys_service import WebhookEncryptionKeysService
        api = Configuration(user_id=int(user_id), authentication_key=auth_key, request_timeout=8, debug=False)
        return int(space), TransactionsService(api), RefundsService(api), WebhookEncryptionKeysService(api)
    except ImportError:
        raise ProviderError(503, "PostFinance Checkout SDK is unavailable") from None


def get_field(value, name, default=None):
    if isinstance(value, dict):
        return value.get(name, default)
    return getattr(value, name, default)


def value_text(value):
    return str(getattr(value, "value", value) or "")


def transaction_amount(transaction):
    amount = get_field(transaction, "total_settled_amount")
    if amount is None:
        amount = get_field(transaction, "completed_amount")
    currency = get_field(transaction, "currency")
    return major_amount(amount, currency), str(currency or "").upper() or None


def transaction_result(transaction, expected_reference=None):
    transaction_id = get_field(transaction, "id")
    if isinstance(transaction_id, bool) or not isinstance(transaction_id, int) or transaction_id <= 0:
        raise ProviderError(502, "PostFinance Checkout returned an invalid transaction")
    reference = str(transaction_id)
    state = value_text(get_field(transaction, "state", "")).upper()
    status = {"COMPLETED": "CAPTURED", "VOIDED": "CANCELLED", "FAILED": "FAILED", "DECLINE": "FAILED"}.get(state, "PENDING")
    amount, currency = transaction_amount(transaction)
    return {"providerReference": expected_reference or reference, "transactionReference": reference,
            "state": status, "amount": amount, "currency": currency, "failureCode": None}


def read_transaction(configuration, reference):
    if not isinstance(reference, str) or not reference.isdecimal() or len(reference) > 20:
        raise ProviderError(400, "Invalid PostFinance transaction reference")
    space, transactions, _, _ = api_services(configuration)
    try:
        return transactions.get_payment_transactions_id(int(reference), space, expand=["group"])
    except Exception:
        raise ProviderError(503, "PostFinance Checkout transaction could not be retrieved") from None


def operation(configuration, name, payload, envelope):
    if name == "capabilities":
        return {"hostedCheckout": True, "authorization": False, "capture": False,
                "cancellation": True, "refunds": True, "webhooks": True,
                "maximumCheckoutExpirySeconds": 86400}
    if name == "listMethods":
        currencies = supported_currencies(configuration)
        country = payload.get("country")
        currency = payload.get("currency")
        if country not in (None, "CH"):
            return {"methods": []}
        if currency is not None and currency not in currencies:
            return {"methods": []}
        return {"methods": [{"methodId": "postfinance_checkout", "displayName": "PostFinance Checkout",
                             "countries": ["CH"], "currencies": currencies, "hostedCheckout": True}]}
    if name == "createCheckout":
        currency = payload.get("currency")
        if currency not in supported_currencies(configuration):
            raise ProviderError(400, "Currency is not enabled for this PostFinance Checkout space")
        if payload.get("paymentMethodId") != "postfinance_checkout":
            raise ProviderError(400, "Selected payment method is not enabled for PostFinance Checkout")
        amount = amount_decimal(payload.get("amount"), currency)
        success_url, cancel_url = payload.get("successUrl"), payload.get("cancelUrl")
        if any(not isinstance(url, str) or not url.startswith("https://") or len(url) > 2000
               for url in (success_url, cancel_url)):
            raise ProviderError(400, "Secure checkout return URLs are required")
        space, transactions, _, _ = api_services(configuration)
        idem = str(envelope.get("idempotencyKey", ""))
        try:
            from postfinancecheckout.models.environment import Environment
            from postfinancecheckout.models.line_item_create import LineItemCreate
            from postfinancecheckout.models.line_item_type import LineItemType
            from postfinancecheckout.models.transaction_create import TransactionCreate
            reference = "ARTIO-" + idem.replace("-", "")
            prior = transactions.get_payment_transactions_search(space, query='merchantReference:"' + reference + '"', limit=2)
            matches = get_field(prior, "data", []) or []
            if len(matches) > 1:
                raise ProviderError(409, "Duplicate merchant reference requires operator review")
            if matches:
                transaction = matches[0]
            else:
                line = LineItemCreate(quantity=1, name="Artio order " + str(payload.get("orderId", ""))[:8],
                                      amount_including_tax=float(amount), type=LineItemType.PRODUCT,
                                      unique_id="artio-order", shipping_required=False)
                test_mode = configuration.get("testMode", True)
                if not isinstance(test_mode, bool):
                    raise ProviderError(400, "Invalid PostFinance environment configuration")
                transaction_request = TransactionCreate(line_items=[line], currency=currency,
                    merchant_reference=reference, success_url=success_url, failed_url=cancel_url,
                    environment=Environment.PREVIEW if test_mode else Environment.LIVE,
                    meta_data={"artioOrderId": str(payload.get("orderId", "")), "artioIdempotencyKey": idem})
                transaction = transactions.post_payment_transactions(space, transaction_request)
            transaction_id = get_field(transaction, "id")
            if isinstance(transaction_id, bool) or not isinstance(transaction_id, int) or transaction_id <= 0:
                raise ProviderError(502, "PostFinance Checkout returned an invalid transaction")
            checkout_url = transactions.get_payment_transactions_id_payment_page_url(transaction_id, space)
            if not isinstance(checkout_url, str) or not checkout_url.startswith("https://"):
                raise ProviderError(502, "PostFinance Checkout returned an invalid payment page")
            return {"providerReference": str(transaction_id), "transactionReference": str(transaction_id),
                    "checkoutUrl": checkout_url, "state": "PENDING"}
        except ProviderError:
            raise
        except Exception:
            raise ProviderError(503, "PostFinance Checkout could not create a hosted transaction") from None
    if name == "cancel":
        reference = payload.get("providerReference")
        if not isinstance(reference, str) or not reference.isdecimal() or len(reference) > 20:
            raise ProviderError(400, "Invalid PostFinance transaction reference")
        space, transactions, _, _ = api_services(configuration)
        try:
            result = transactions.post_payment_transactions_id_void_online(int(reference), space)
            return {"providerReference": reference, "transactionReference": reference, "state": "CANCELLED",
                    "amount": None, "currency": None, "failureCode": None,
                    "providerOperationReference": str(get_field(result, "id", "")) or None}
        except Exception:
            raise ProviderError(503, "PostFinance Checkout could not void the transaction") from None
    if name == "refund":
        reference = payload.get("providerReference")
        currency = payload.get("currency")
        if not isinstance(reference, str) or not reference.isdecimal() or len(reference) > 20:
            raise ProviderError(400, "Invalid PostFinance transaction reference")
        if currency not in supported_currencies(configuration):
            raise ProviderError(400, "Currency is not enabled for this PostFinance Checkout space")
        amount = amount_decimal(payload.get("amount"), currency)
        _, _, refunds, _ = api_services(configuration)
        try:
            from postfinancecheckout.models.refund_create import RefundCreate
            from postfinancecheckout.models.refund_type import RefundType
            refund = refunds.post_payment_refunds(int(configuration["spaceId"]), RefundCreate(
                transaction=int(reference), amount=float(amount), external_id=str(envelope.get("idempotencyKey", "")),
                type=RefundType.MERCHANT_INITIATED_ONLINE))
            refund_state = value_text(get_field(refund, "state", "")).upper()
            if refund_state != "SUCCESSFUL":
                raise ProviderError(503, "PostFinance Checkout refund is not yet confirmed")
            refund_id = get_field(refund, "id")
            if isinstance(refund_id, bool) or not isinstance(refund_id, int) or refund_id <= 0:
                raise ProviderError(502, "PostFinance Checkout returned an invalid refund")
            return {"providerReference": str(refund_id), "transactionReference": reference,
                    "state": "REFUNDED", "amount": format(amount, f".{currency_digits(currency)}f"), "currency": currency}
        except ProviderError:
            raise
        except Exception:
            raise ProviderError(503, "PostFinance Checkout could not confirm the refund") from None
    if name == "reconcile":
        transaction = read_transaction(configuration, payload.get("providerReference"))
        result = transaction_result(transaction, str(get_field(transaction, "id")))
        return {"result": result if result["state"] != "PENDING" else None}
    if name == "verifyWebhook":
        try:
            body = base64.b64decode(payload.get("rawBodyBase64", ""), validate=True)
            event = json.loads(body)
        except (ValueError, TypeError, UnicodeDecodeError, json.JSONDecodeError):
            raise ProviderError(400, "Invalid PostFinance webhook payload") from None
        headers = payload.get("headers") or {}
        signature = headers.get("x-signature") or payload.get("signature")
        if not isinstance(signature, str) or len(signature) > 2048:
            raise ProviderError(400, "PostFinance webhook signature is missing")
        event_id, transaction_id = event.get("eventId"), event.get("entityId")
        try:
            if not isinstance(event_id, int) or isinstance(event_id, bool) or event_id <= 0:
                raise ValueError()
            if not isinstance(transaction_id, int) or isinstance(transaction_id, bool) or transaction_id <= 0:
                raise ValueError()
            space, _, _, encryption_keys = api_services(configuration)
        except ProviderError:
            raise
        except ValueError:
            raise ProviderError(400, "Invalid PostFinance webhook identity") from None
        if event.get("spaceId") != space or event.get("listenerEntityTechnicalName") != "Transaction":
            raise ProviderError(400, "PostFinance webhook does not match the configured space")
        try:
            if not encryption_keys.is_content_valid(signature, body.decode("utf-8")):
                raise ProviderError(400, "Invalid PostFinance webhook signature")
            transaction = read_transaction(configuration, str(transaction_id))
        except ProviderError:
            raise
        except Exception:
            raise ProviderError(400, "Invalid PostFinance webhook signature or transaction") from None
        state = transaction_result(transaction)
        return {"providerEventId": str(event_id), "eventType": "transaction.state." + value_text(get_field(transaction, "state", "UNKNOWN")),
                "providerReference": str(transaction_id), "transactionReference": str(transaction_id),
                "state": state["state"], "amount": state["amount"], "currency": state["currency"], "verified": True}
    if name == "capture":
        raise ProviderError(409, "Hosted PostFinance Checkout does not expose separate capture")
    raise ProviderError(400, "Unsupported payment operation")


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            return self.respond(200, {"status": "healthy"})
        if self.path == "/diagnostics":
            return self.respond(200, {"status": "ready", "provider": "postfinance-checkout", "secrets": "redacted"})
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
