"""Provider-neutral catalog.search.v1 plugin backed by its private OpenSearch sidecar."""

import hmac
import json
import os
import re
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

MAX_BODY = 1_048_576
ALIAS = "artio-products"
OPENSEARCH = "http://artio-svc-ca8fa8cc1934-opensearch:9200"
INDEX_MAPPING = {
    "mappings": {"properties": {
        "productId": {"type": "keyword"}, "sku": {"type": "keyword"},
        "name": {"type": "text"}, "description": {"type": "text"},
        "category": {"type": "keyword"}, "brand": {"type": "keyword"},
        "locale": {"type": "keyword"}, "active": {"type": "boolean"}
    }}
}
ALIAS_LOCK = threading.Lock()


class PluginError(Exception):
    def __init__(self, status, code):
        super().__init__(code)
        self.status = status
        self.code = code


def request(method, path, body=None):
    data = None if body is None else json.dumps(body, separators=(",", ":")).encode()
    req = Request(OPENSEARCH + path, data=data, method=method,
                  headers={"Content-Type": "application/json"} if data is not None else {})
    try:
        with urlopen(req, timeout=5) as response:
            content = response.read(MAX_BODY + 1)
            if len(content) > MAX_BODY:
                raise PluginError(502, "search_response_too_large")
            return json.loads(content or b"{}")
    except HTTPError as error:
        if error.code == 404:
            raise PluginError(404, "search_resource_not_found") from None
        if error.code in (400, 409):
            raise PluginError(409, "search_index_conflict") from None
        raise PluginError(503, "search_backend_unavailable") from None
    except (URLError, TimeoutError, OSError, json.JSONDecodeError):
        raise PluginError(503, "search_backend_unavailable") from None


def validate_document(payload):
    document = payload.get("document")
    if not isinstance(document, dict):
        raise PluginError(400, "invalid_document")
    product_id = document.get("productId")
    try:
        normalized_id = str(uuid.UUID(product_id))
    except (ValueError, TypeError, AttributeError):
        raise PluginError(400, "invalid_product_id") from None
    locale = document.get("locale")
    sku = document.get("sku")
    name = document.get("name")
    if not isinstance(locale, str) or not re.fullmatch(r"[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8})*|base", locale):
        raise PluginError(400, "invalid_locale")
    for field, value, maximum in (("sku", sku, 128), ("name", name, 500)):
        if not isinstance(value, str) or not value.strip() or len(value) > maximum:
            raise PluginError(400, "invalid_" + field)
    description = document.get("description")
    if description is not None and (not isinstance(description, str) or len(description) > 20_000):
        raise PluginError(400, "invalid_description")
    return {"productId": normalized_id, "sku": sku, "name": name,
            "description": description or "", "category": document.get("category"),
            "brand": document.get("brand"), "locale": locale,
            "active": payload.get("active") is True}


def index_document(payload):
    document = validate_document(payload)
    index = payload.get("generation") or ALIAS
    if not isinstance(index, str) or not re.fullmatch(r"artio-products(?:-generation-[a-f0-9]{32})?", index):
        raise PluginError(400, "invalid_rebuild_generation")
    if not payload.get("generation"):
        ensure_alias()
    doc_id = document["productId"] + "-" + document["locale"]
    request("PUT", f"/{quote(index)}/_doc/{quote(doc_id)}?refresh=wait_for", document)
    return {"indexed": True}


def remove_product(payload):
    product_id = payload.get("productId")
    try:
        product_id = str(uuid.UUID(product_id))
    except (ValueError, TypeError, AttributeError):
        raise PluginError(400, "invalid_product_id") from None
    try:
        request("POST", f"/{ALIAS}/_delete_by_query?refresh=wait_for", {"query": {"term": {"productId": product_id}}})
    except PluginError as missing:
        if missing.status != 404:
            raise
    return {"removed": True}


def search_products(payload):
    search = payload.get("request")
    if not isinstance(search, dict):
        raise PluginError(400, "invalid_search_request")
    query = search.get("query")
    locale = search.get("locale")
    size = search.get("size")
    page = search.get("page", 0)
    if not isinstance(query, str) or len(query) > 500 or not isinstance(locale, str):
        raise PluginError(400, "invalid_search_request")
    if not isinstance(size, int) or not 1 <= size <= 100 or not isinstance(page, int) or not 0 <= page <= 10_000:
        raise PluginError(400, "invalid_search_bounds")
    for selected_locale in dict.fromkeys((locale, "en", "base")):
        try:
            result = request("POST", f"/{ALIAS}/_search", {
            "from": page * size, "size": size,
            "query": {"bool": {"must": [{"multi_match": {"query": query,
                       "fields": ["name^3", "sku^2", "description"]}}],
                      "filter": [{"term": {"locale": selected_locale}}, {"term": {"active": True}}]}}
            })
        except PluginError as missing:
            if missing.status == 404:
                return {"productIds": []}
            raise
        hits = result.get("hits", {}).get("hits", [])
        if hits:
            ids = [hit.get("_source", {}).get("productId") for hit in hits]
            return {"productIds": list(dict.fromkeys(value for value in ids if isinstance(value, str)))[:size]}
    return {"productIds": []}


def ensure_alias():
    with ALIAS_LOCK:
        try:
            request("GET", f"/_alias/{ALIAS}")
            return
        except PluginError as missing:
            if missing.status != 404:
                raise
        index = ALIAS + "-generation-" + uuid.uuid4().hex
        request("PUT", "/" + index, INDEX_MAPPING)
        request("POST", "/_aliases", {"actions": [{"add": {"index": index, "alias": ALIAS}}]})


def begin_rebuild():
    generation = "artio-products-generation-" + uuid.uuid4().hex
    request("PUT", "/" + generation, INDEX_MAPPING)
    return {"generation": generation}


def commit_rebuild(payload):
    generation = payload.get("generation")
    if not isinstance(generation, str) or not re.fullmatch(r"artio-products-generation-[a-f0-9]{32}", generation):
        raise PluginError(400, "invalid_rebuild_generation")
    request("POST", f"/{generation}/_refresh")
    old_indices = []
    try:
        aliases = request("GET", f"/_alias/{ALIAS}")
        old_indices = [name for name, metadata in aliases.items() if ALIAS in metadata.get("aliases", {})]
    except PluginError as error:
        if error.status != 404:
            raise
        try:
            request("GET", f"/{ALIAS}")
            old_indices = [ALIAS]
        except PluginError as missing:
            if missing.status != 404:
                raise
    actions = [{"remove": {"index": index, "alias": ALIAS}} for index in old_indices if index != ALIAS]
    if ALIAS in old_indices:
        actions.append({"remove_index": {"index": ALIAS}})
    actions.append({"add": {"index": generation, "alias": ALIAS}})
    request("POST", "/_aliases", {"actions": actions})
    for old in old_indices:
        if old != ALIAS:
            try:
                request("DELETE", "/" + quote(old))
            except PluginError:
                pass
    return {"committed": True}


def abort_rebuild(payload):
    generation = payload.get("generation")
    if not isinstance(generation, str) or not re.fullmatch(r"artio-products-generation-[a-f0-9]{32}", generation):
        raise PluginError(400, "invalid_rebuild_generation")
    try:
        request("DELETE", "/" + quote(generation))
    except PluginError as error:
        if error.status != 404:
            raise
    return {"aborted": True}


def invoke(operation, payload):
    handlers = {"index": lambda: index_document(payload), "remove": lambda: remove_product(payload),
                "search": lambda: search_products(payload), "beginRebuild": begin_rebuild,
                "commitRebuild": lambda: commit_rebuild(payload), "abortRebuild": lambda: abort_rebuild(payload)}
    handler = handlers.get(operation)
    if handler is None:
        raise PluginError(400, "unsupported_operation")
    return handler()


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            return self.respond(200, {"status": "healthy"})
        if self.path == "/diagnostics":
            return self.respond(200, {"status": "ready", "provider": "opensearch", "secrets": "redacted"})
        return self.respond(404, {"error": "not_found"})

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
            if not 2 <= length <= MAX_BODY:
                return self.respond(413, {"error": "request_too_large"})
            envelope = json.loads(self.rfile.read(length))
            if not isinstance(envelope, dict) or envelope.get("protocolVersion") != 1 or envelope.get("extensionPoint") != "catalog.search.v1":
                return self.respond(400, {"error": "unsupported_contract"})
            try:
                if str(uuid.UUID(envelope.get("idempotencyKey", ""))) != envelope.get("idempotencyKey", "").lower():
                    raise ValueError()
            except (ValueError, AttributeError, TypeError):
                return self.respond(400, {"error": "invalid_idempotency_key"})
            payload = envelope.get("payload") or {}
            if not isinstance(payload, dict):
                return self.respond(400, {"error": "invalid_payload"})
            self.respond(200, invoke(envelope.get("operation"), payload))
        except PluginError as error:
            self.respond(error.status, {"error": error.code})
        except (ValueError, TypeError, json.JSONDecodeError):
            self.respond(400, {"error": "invalid_request"})
        except Exception:
            self.respond(500, {"error": "internal_error"})

    def respond(self, status, value):
        body = json.dumps(value, separators=(",", ":")).encode()
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
