"""Authenticated Artio storage.object.v1 adapter for a scoped S3/MinIO identity."""

import hashlib
import hmac
import json
import os
import re
import tempfile
import threading
import uuid
from urllib.parse import urlsplit
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import boto3
from botocore.config import Config
from botocore.exceptions import BotoCoreError, ClientError, ReadTimeoutError

MAX_BODY = 1_048_576
CHUNK_BYTES = 512 * 1024
MAX_CHUNKS = 20
MAX_OBJECT_BYTES = CHUNK_BYTES * MAX_CHUNKS
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
    for name in ("staging", "records", "operations", "tombstones"):
        (root / name).mkdir(parents=True, exist_ok=True)
    return root


def _atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".artio-minio-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            json.dump(value, output, separators=(",", ":"))
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def _uuid(value, code):
    try:
        normalized = str(uuid.UUID(value))
    except (ValueError, TypeError, AttributeError):
        raise StorageError(400, code) from None
    if normalized != value.lower():
        raise StorageError(400, code)
    return normalized


def _key(payload):
    key = payload.get("key")
    if not isinstance(key, str) or not KEY.fullmatch(key):
        raise StorageError(400, "invalid_object_key")
    return key


def _placement_role(payload):
    if payload.get("placementRole") not in ("PRIMARY", "REPLICA"):
        raise StorageError(400, "invalid_placement_role")


def _client():
    secret_path = Path(os.environ.get("ARTIO_S3_CREDENTIALS_FILE", "/run/secrets/s3-credentials.json"))
    try:
        credentials = json.loads(secret_path.read_text(encoding="utf-8"))
        access_key = credentials["accessKeyId"]
        secret_key = credentials["secretAccessKey"]
    except (OSError, ValueError, KeyError, TypeError):
        raise StorageError(503, "credentials_unavailable") from None
    if not isinstance(access_key, str) or not isinstance(secret_key, str) or not access_key or not secret_key:
        raise StorageError(503, "credentials_unavailable")
    endpoint = os.environ.get("ARTIO_S3_ENDPOINT", "").strip()
    parsed_endpoint = urlsplit(endpoint)
    if (parsed_endpoint.scheme != "https" or not parsed_endpoint.hostname or parsed_endpoint.username
            or parsed_endpoint.password or parsed_endpoint.query or parsed_endpoint.fragment):
        raise StorageError(400, "invalid_endpoint")
    path_style = os.environ.get("ARTIO_S3_ADDRESSING_STYLE", "path")
    if path_style not in ("path", "virtual"):
        raise StorageError(400, "invalid_addressing_style")
    session = boto3.session.Session(aws_access_key_id=access_key, aws_secret_access_key=secret_key)
    return session.client(
        "s3",
        endpoint_url=endpoint,
        region_name=os.environ.get("ARTIO_S3_REGION", "us-east-1"),
        config=Config(
            signature_version="s3v4",
            s3={"addressing_style": path_style},
            connect_timeout=3,
            read_timeout=10,
            retries={"mode": "standard", "max_attempts": 2},
        ),
    )


def _bucket():
    bucket = os.environ.get("ARTIO_S3_BUCKET", "")
    prefix = os.environ.get("ARTIO_S3_PREFIX", "artio").strip("/")
    if not bucket or not re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", bucket):
        raise StorageError(400, "invalid_bucket")
    if not prefix or any(part in ("", ".", "..") for part in prefix.split("/")):
        raise StorageError(400, "invalid_prefix")
    return bucket, prefix


def _provider_key(prefix, key):
    return prefix + "/" + key


def _assert_provider_identity(root, bucket, prefix):
    endpoint = os.environ.get("ARTIO_S3_ENDPOINT", "").strip()
    identity = hashlib.sha256(json.dumps({"endpoint": endpoint, "bucket": bucket, "prefix": prefix},
                                         sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    identity_path = root / "provider-identity.json"
    if identity_path.exists():
        saved = json.loads(identity_path.read_text(encoding="utf-8"))
        if hmac.compare_digest(saved.get("sha256", ""), identity):
            return
        has_objects = any((root / "records").glob("*.json")) or any((root / "tombstones").glob("*.json"))
        if has_objects:
            raise StorageError(409, "storage_identity_conflict")
    _atomic_json(identity_path, {"sha256": identity})


def _s3_call(call):
    try:
        return call()
    except ClientError as error:
        code = error.response.get("Error", {}).get("Code", "")
        if code in ("NoSuchKey", "NoSuchVersion", "NotFound", "404"):
            raise StorageError(404, "object_not_found") from None
        if code in ("AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch", "AllAccessDisabled"):
            raise StorageError(403, "provider_denied") from None
        if code in ("SlowDown", "Throttling", "RequestLimitExceeded"):
            raise StorageError(429, "provider_throttled") from None
        raise StorageError(503, "provider_unavailable") from None
    except ReadTimeoutError:
        raise StorageError(504, "provider_timeout") from None
    except (BotoCoreError, TimeoutError, OSError):
        raise StorageError(503, "provider_unavailable") from None


def _verified_provider_version(s3, bucket, key, expected_length, expected_sha256):
    head = _s3_call(lambda: s3.head_object(Bucket=bucket, Key=key))
    if (head.get("ContentLength") != expected_length
            or head.get("Metadata", {}).get("artio-sha256") != expected_sha256):
        raise StorageError(409, "object_version_conflict")
    version = head.get("VersionId")
    args = {"Bucket": bucket, "Key": key}
    if version:
        args["VersionId"] = version
    response = _s3_call(lambda: s3.get_object(**args))
    digest = hashlib.sha256()
    length = 0
    try:
        while block := response["Body"].read(64 * 1024):
            length += len(block)
            if length > expected_length:
                raise StorageError(409, "object_version_conflict")
            digest.update(block)
    except ReadTimeoutError:
        raise StorageError(504, "provider_timeout") from None
    except (BotoCoreError, TimeoutError, OSError):
        raise StorageError(503, "provider_unavailable") from None
    finally:
        response["Body"].close()
    if length != expected_length or not hmac.compare_digest(digest.hexdigest(), expected_sha256):
        raise StorageError(409, "object_version_conflict")
    return version or "unversioned"


def _save_operation(root, identity, fingerprint, result):
    _atomic_json(root / "operations" / (identity + ".json"), {"fingerprint": fingerprint, "result": result})


def invoke(operation, payload, idempotency_key, root=None, client=None):
    if not isinstance(payload, dict):
        raise StorageError(400, "invalid_request")
    root = root or root_path()
    identity = _uuid(idempotency_key, "invalid_idempotency_key")
    key = _key(payload)
    _placement_role(payload)
    bucket, prefix = _bucket()
    object_key = _provider_key(prefix, key)
    record_path = root / "records" / (key + ".json")
    tombstone_path = root / "tombstones" / (key + ".json")
    operation_path = root / "operations" / (identity + ".json")
    fingerprint = hashlib.sha256(json.dumps({"operation": operation, "payload": payload}, sort_keys=True,
                                             separators=(",", ":")).encode()).hexdigest()
    s3 = client or _client()

    with LOCK:
        _assert_provider_identity(root, bucket, prefix)
        if operation_path.exists():
            saved = json.loads(operation_path.read_text(encoding="utf-8"))
            if not hmac.compare_digest(saved.get("fingerprint", ""), fingerprint):
                raise StorageError(409, "idempotency_conflict")
            return saved["result"]
        if operation != "delete" and tombstone_path.exists():
            raise StorageError(404, "object_tombstoned")

        if operation == "put":
            upload_id = _uuid(payload.get("uploadId"), "invalid_upload_id")
            checksum = payload.get("sha256")
            index, total = payload.get("chunkIndex"), payload.get("totalChunks")
            complete, encoded = payload.get("complete"), payload.get("chunkBase64")
            if (not isinstance(checksum, str) or not SHA256.fullmatch(checksum)
                    or not isinstance(index, int) or isinstance(index, bool)
                    or not isinstance(total, int) or isinstance(total, bool) or not 1 <= total <= MAX_CHUNKS
                    or not 0 <= index < total or not isinstance(complete, bool) or complete != (index == total - 1)
                    or not isinstance(encoded, str)):
                raise StorageError(400, "invalid_put_metadata")
            import base64
            try:
                chunk = base64.b64decode(encoded, validate=True)
            except (ValueError, TypeError):
                raise StorageError(400, "invalid_chunk_encoding") from None
            expected_size = min(CHUNK_BYTES, MAX_OBJECT_BYTES - index * CHUNK_BYTES)
            if not chunk or len(chunk) > CHUNK_BYTES or index < total - 1 and len(chunk) != CHUNK_BYTES:
                raise StorageError(413, "invalid_chunk_size")
            upload_dir = root / "staging" / upload_id
            upload_dir.mkdir(parents=True, exist_ok=True)
            metadata_path = upload_dir / "upload.json"
            request_metadata = {"key": key, "sha256": checksum, "totalChunks": total}
            if metadata_path.exists() and json.loads(metadata_path.read_text(encoding="utf-8")) != request_metadata:
                raise StorageError(409, "upload_identity_conflict")
            chunk_path = upload_dir / (str(index) + ".chunk")
            if chunk_path.exists() and chunk_path.read_bytes() != chunk:
                raise StorageError(409, "upload_chunk_conflict")
            chunk_path.write_bytes(chunk)
            _atomic_json(metadata_path, request_metadata)
            if not complete:
                result = {"sha256": checksum, "versionId": "pending"}
            else:
                if any(not (upload_dir / (str(part) + ".chunk")).exists() for part in range(total)):
                    raise StorageError(409, "upload_chunks_missing")
                length = sum((upload_dir / (str(part) + ".chunk")).stat().st_size for part in range(total))
                if not 0 < length <= MAX_OBJECT_BYTES:
                    raise StorageError(413, "object_too_large")
                with tempfile.TemporaryFile() as assembled:
                    digest = hashlib.sha256()
                    for part in range(total):
                        with (upload_dir / (str(part) + ".chunk")).open("rb") as source:
                            while block := source.read(CHUNK_BYTES):
                                digest.update(block)
                                assembled.write(block)
                    if not hmac.compare_digest(digest.hexdigest(), checksum):
                        raise StorageError(422, "object_checksum_mismatch")
                    if record_path.exists():
                        existing = json.loads(record_path.read_text(encoding="utf-8"))
                        if existing["sha256"] != checksum or existing["byteLength"] != length:
                            raise StorageError(409, "object_version_conflict")
                        version = existing["versionId"]
                    else:
                        version = None
                        try:
                            # Reconcile a prior PUT whose response or local metadata write was lost.
                            version = _verified_provider_version(s3, bucket, object_key, length, checksum)
                        except StorageError as failure:
                            if failure.status != 404:
                                raise
                        if version is None:
                            assembled.seek(0)
                            response = _s3_call(lambda: s3.put_object(
                                Bucket=bucket, Key=object_key, Body=assembled, ContentLength=length,
                                Metadata={"artio-sha256": checksum},
                            ))
                            version = response.get("VersionId") or "unversioned"
                        _atomic_json(record_path, {"key": key, "sha256": checksum, "byteLength": length,
                                                    "versionId": version})
                    result = {"sha256": checksum, "versionId": version}
                for item in upload_dir.iterdir():
                    item.unlink(missing_ok=True)
                upload_dir.rmdir()
            _save_operation(root, identity, fingerprint, result)
        elif operation in ("get", "head"):
            if not record_path.exists():
                raise StorageError(404, "object_not_found")
            record = json.loads(record_path.read_text(encoding="utf-8"))
            version_args = {} if record["versionId"] == "unversioned" else {"VersionId": record["versionId"]}
            if operation == "head":
                head = _s3_call(lambda: s3.head_object(Bucket=bucket, Key=object_key, **version_args))
                if (head.get("ContentLength") != record["byteLength"]
                        or head.get("Metadata", {}).get("artio-sha256") != record["sha256"]):
                    raise StorageError(422, "object_integrity_failure")
                result = {"sha256": record["sha256"], "byteLength": record["byteLength"],
                          "versionId": record["versionId"]}
            else:
                chunk_index = payload.get("chunkIndex")
                count = max(1, (record["byteLength"] + CHUNK_BYTES - 1) // CHUNK_BYTES)
                if not isinstance(chunk_index, int) or isinstance(chunk_index, bool) or not 0 <= chunk_index < count:
                    raise StorageError(400, "invalid_chunk_index")
                start = chunk_index * CHUNK_BYTES
                end = min(record["byteLength"], start + CHUNK_BYTES) - 1
                response = _s3_call(lambda: s3.get_object(Bucket=bucket, Key=object_key, Range=f"bytes={start}-{end}", **version_args))
                data = response["Body"].read(CHUNK_BYTES + 1)
                response["Body"].close()
                if len(data) != end - start + 1:
                    raise StorageError(422, "object_integrity_failure")
                import base64
                result = {"chunkBase64": base64.b64encode(data).decode("ascii"), "chunkIndex": chunk_index,
                          "totalChunks": count, "sha256": record["sha256"], "versionId": record["versionId"]}
            _save_operation(root, identity, fingerprint, result)
        elif operation == "delete":
            tombstone_id = _uuid(payload.get("tombstoneId"), "invalid_tombstone_id")
            if tombstone_path.exists():
                saved_tombstone = json.loads(tombstone_path.read_text(encoding="utf-8"))
                if saved_tombstone.get("tombstoneId") != tombstone_id:
                    raise StorageError(409, "tombstone_conflict")
            if record_path.exists():
                record = json.loads(record_path.read_text(encoding="utf-8"))
                args = {"Bucket": bucket, "Key": object_key}
                if record["versionId"] != "unversioned":
                    args["VersionId"] = record["versionId"]
                _s3_call(lambda: s3.delete_object(**args))
                record_path.unlink(missing_ok=True)
            _atomic_json(tombstone_path, {"tombstoneId": tombstone_id, "key": key})
            result = {"deleted": True, "tombstoneId": tombstone_id}
            _save_operation(root, identity, fingerprint, result)
        else:
            raise StorageError(400, "unsupported_operation")
        return result


def diagnose(client=None):
    """Prove scoped S3 write/read/checksum/delete access using one temporary object."""
    s3 = client or _client()
    bucket, prefix = _bucket()
    root = root_path()
    with LOCK:
        _assert_provider_identity(root, bucket, prefix)
    body = os.urandom(32)
    checksum = hashlib.sha256(body).hexdigest()
    diagnostic_key = _provider_key(prefix, ".artio-diagnostics/" + str(uuid.uuid4()))
    version = None
    try:
        response = _s3_call(lambda: s3.put_object(Bucket=bucket, Key=diagnostic_key, Body=body,
                                                 ContentLength=len(body), Metadata={"artio-sha256": checksum}))
        version = response.get("VersionId")
        head_args = {"Bucket": bucket, "Key": diagnostic_key}
        if version:
            head_args["VersionId"] = version
        head = _s3_call(lambda: s3.head_object(**head_args))
        if (head.get("ContentLength") != len(body)
                or head.get("Metadata", {}).get("artio-sha256") != checksum):
            raise StorageError(422, "object_integrity_failure")
        result = _s3_call(lambda: s3.get_object(**head_args, Range=f"bytes=0-{len(body) - 1}"))
        try:
            received = result["Body"].read(len(body) + 1)
        finally:
            result["Body"].close()
        if not hmac.compare_digest(hashlib.sha256(received).hexdigest(), checksum):
            raise StorageError(422, "object_integrity_failure")
        return {"status": "ready", "provider": "artio-minio-object-storage"}
    finally:
        try:
            delete_args = {"Bucket": bucket, "Key": diagnostic_key}
            if version:
                delete_args["VersionId"] = version
            _s3_call(lambda: s3.delete_object(**delete_args))
        except StorageError:
            pass


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path not in ("/health", "/diagnostics"):
            return self.respond(404, {"error": "not_found"})
        if self.path == "/health":
            return self.respond(200, {"status": "healthy", "provider": "artio-minio-object-storage"})
        try:
            self.respond(200, diagnose())
        except StorageError as error:
            self.respond(error.status, {"error": error.code})

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
            if (not isinstance(envelope, dict) or envelope.get("protocolVersion") != 1
                    or envelope.get("extensionPoint") != "storage.object.v1"
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
