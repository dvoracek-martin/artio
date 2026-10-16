"""Reference isolated storage.object.v1 plugin with durable chunk assembly and tombstones."""

import base64
import hashlib
import hmac
import json
import os
import re
import tempfile
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MAX_BODY = 1_048_576
CHUNK_BYTES = 512 * 1024
MAX_CHUNKS = 20
KEY = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|png)$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
LOCK = threading.RLock()


class StorageError(Exception):
    def __init__(self, status, code):
        super().__init__(code)
        self.status = status
        self.code = code


def root_path():
    root = Path(os.environ.get("ARTIO_OBJECT_STORAGE_PATH", "/data")).resolve()
    root.mkdir(parents=True, exist_ok=True)
    return root


def _atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".artio-object-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            json.dump(value, output, separators=(",", ":"))
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def _key(payload):
    key = payload.get("key")
    if not isinstance(key, str) or not KEY.fullmatch(key):
        raise StorageError(400, "invalid_object_key")
    return key


def _uuid(value, code):
    try:
        normalized = str(uuid.UUID(value))
    except (ValueError, TypeError, AttributeError):
        raise StorageError(400, code) from None
    if normalized != value.lower():
        raise StorageError(400, code)
    return normalized


def _dirs(root):
    objects = root / "objects"
    staging = root / "staging"
    tombstones = root / "tombstones"
    operations = root / "operations"
    for folder in (objects, staging, tombstones, operations):
        folder.mkdir(parents=True, exist_ok=True)
    return objects, staging, tombstones, operations


def _object_paths(root, key):
    objects, _, tombstones, _ = _dirs(root)
    stem = key[:-4]
    return objects / (key + ".blob"), objects / (key + ".json"), tombstones / (stem + ".json")


def _save_operation(operations, identity, fingerprint, result):
    _atomic_json(operations / (identity + ".json"), {"fingerprint": fingerprint, "result": result})


def invoke(operation, payload, idempotency_key, root=None):
    if not isinstance(payload, dict):
        raise StorageError(400, "invalid_request")
    root = root or root_path()
    objects, staging, tombstones, operations = _dirs(root)
    identity = _uuid(idempotency_key, "invalid_idempotency_key")
    key = _key(payload)
    object_file, metadata_file, tombstone_file = _object_paths(root, key)
    fingerprint = hashlib.sha256(json.dumps({"operation": operation, "payload": payload}, sort_keys=True,
                                            separators=(",", ":")).encode()).hexdigest()
    operation_record = operations / (identity + ".json")
    with LOCK:
        if operation != "delete" and tombstone_file.exists():
            raise StorageError(404, "object_tombstoned")
        if operation_record.exists():
            saved = json.loads(operation_record.read_text(encoding="utf-8"))
            if not hmac.compare_digest(saved.get("fingerprint", ""), fingerprint):
                raise StorageError(409, "idempotency_conflict")
            return saved["result"]

        if operation == "put":
            if tombstone_file.exists():
                raise StorageError(409, "object_tombstoned")
            upload_id = _uuid(payload.get("uploadId"), "invalid_upload_id")
            checksum = payload.get("sha256")
            index = payload.get("chunkIndex")
            total = payload.get("totalChunks")
            complete = payload.get("complete")
            role = payload.get("placementRole")
            encoded = payload.get("chunkBase64")
            if (not isinstance(checksum, str) or not SHA256.fullmatch(checksum)
                    or not isinstance(index, int) or isinstance(index, bool) or not isinstance(total, int) or isinstance(total, bool)
                    or total < 1 or total > MAX_CHUNKS or index < 0 or index >= total
                    or not isinstance(complete, bool) or complete != (index == total - 1)
                    or role not in ("PRIMARY", "REPLICA") or not isinstance(encoded, str)):
                raise StorageError(400, "invalid_put_metadata")
            try:
                chunk = base64.b64decode(encoded, validate=True)
            except (ValueError, TypeError):
                raise StorageError(400, "invalid_chunk_encoding") from None
            if not chunk or len(chunk) > CHUNK_BYTES:
                raise StorageError(413, "invalid_chunk_size")
            chunk_hash = hashlib.sha256(chunk).hexdigest()
            upload_dir = staging / upload_id
            upload_dir.mkdir(parents=True, exist_ok=True)
            upload_metadata = upload_dir / "upload.json"
            metadata = {"key": key, "sha256": checksum, "totalChunks": total, "chunks": {}}
            if upload_metadata.exists():
                existing = json.loads(upload_metadata.read_text(encoding="utf-8"))
                if existing["key"] != key or existing["sha256"] != checksum or existing["totalChunks"] != total:
                    raise StorageError(409, "upload_identity_conflict")
                metadata = existing
            chunk_path = upload_dir / (str(index) + ".chunk")
            chunk_path.write_bytes(chunk)
            metadata["chunks"][str(index)] = chunk_hash
            _atomic_json(upload_metadata, metadata)
            result = {"sha256": checksum, "versionId": "pending"}
            if complete:
                if len(metadata["chunks"]) != total:
                    raise StorageError(409, "upload_chunks_missing")
                assembled = b"".join((upload_dir / (str(chunk_index) + ".chunk")).read_bytes()
                                      for chunk_index in range(total))
                if not hmac.compare_digest(hashlib.sha256(assembled).hexdigest(), checksum):
                    raise StorageError(422, "object_checksum_mismatch")
                if metadata_file.exists():
                    current = json.loads(metadata_file.read_text(encoding="utf-8"))
                    if current["sha256"] != checksum:
                        raise StorageError(409, "object_version_conflict")
                    version = current["versionId"]
                else:
                    version = str(uuid.uuid4())
                temp_object = objects / (key + ".tmp")
                temp_object.write_bytes(assembled)
                os.replace(temp_object, object_file)
                _atomic_json(metadata_file, {"sha256": checksum, "byteLength": len(assembled), "versionId": version})
                result = {"sha256": checksum, "versionId": version}
                for item in upload_dir.iterdir():
                    item.unlink(missing_ok=True)
                upload_dir.rmdir()
            _save_operation(operations, identity, fingerprint, result)
        elif operation == "get":
            if not object_file.exists() or not metadata_file.exists():
                raise StorageError(404, "object_not_found")
            if payload.get("placementRole") not in ("PRIMARY", "REPLICA"):
                raise StorageError(400, "invalid_placement_role")
            metadata = json.loads(metadata_file.read_text(encoding="utf-8"))
            index = payload.get("chunkIndex")
            total = (metadata["byteLength"] + CHUNK_BYTES - 1) // CHUNK_BYTES
            if not isinstance(index, int) or isinstance(index, bool) or index < 0 or index >= total:
                raise StorageError(400, "invalid_chunk_index")
            with object_file.open("rb") as source:
                source.seek(index * CHUNK_BYTES)
                chunk = source.read(CHUNK_BYTES)
            result = {"chunkBase64": base64.b64encode(chunk).decode("ascii"), "chunkIndex": index,
                      "totalChunks": total, "sha256": metadata["sha256"], "versionId": metadata["versionId"]}
            _save_operation(operations, identity, fingerprint, result)
        elif operation == "head":
            if not object_file.exists() or not metadata_file.exists():
                raise StorageError(404, "object_not_found")
            if payload.get("placementRole") not in ("PRIMARY", "REPLICA"):
                raise StorageError(400, "invalid_placement_role")
            metadata = json.loads(metadata_file.read_text(encoding="utf-8"))
            result = {"sha256": metadata["sha256"], "byteLength": metadata["byteLength"], "versionId": metadata["versionId"]}
            _save_operation(operations, identity, fingerprint, result)
        elif operation == "delete":
            tombstone_id = _uuid(payload.get("tombstoneId"), "invalid_tombstone_id")
            role = payload.get("placementRole")
            if role not in ("PRIMARY", "REPLICA"):
                raise StorageError(400, "invalid_placement_role")
            _atomic_json(tombstone_file, {"tombstoneId": tombstone_id, "key": key})
            object_file.unlink(missing_ok=True)
            metadata_file.unlink(missing_ok=True)
            result = {"deleted": True, "tombstoneId": tombstone_id}
            _save_operation(operations, identity, fingerprint, result)
        else:
            raise StorageError(400, "unsupported_operation")
        return result


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path not in ("/health", "/diagnostics"):
            return self.respond(404, {"error": "not_found"})
        self.respond(200, {"status": "healthy" if self.path == "/health" else "ready", "provider": "artio-object-storage"})

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
            if (envelope.get("protocolVersion") != 1 or envelope.get("extensionPoint") != "storage.object.v1"
                    or not isinstance(envelope.get("operation"), str)):
                return self.respond(400, {"error": "unsupported_contract"})
            result = invoke(envelope["operation"], envelope.get("payload") or {}, envelope.get("idempotencyKey"))
            self.respond(200, result)
        except StorageError as error:
            self.respond(error.status, {"error": error.code})
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
