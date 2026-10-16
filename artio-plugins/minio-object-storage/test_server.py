import base64
import hashlib
import json
import os
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

import server


class FakeBody:
    def __init__(self, value):
        self.value = value

    def read(self, length=-1):
        value, self.value = self.value[:length], self.value[length:]
        return value

    def close(self):
        pass


class FakeS3:
    def __init__(self):
        self.objects = {}

    def put_object(self, Bucket, Key, Body, ContentLength, Metadata):
        if Key.startswith(".artio-diagnostics-negative/"):
            from botocore.exceptions import ClientError
            raise ClientError({"Error": {"Code": "AccessDenied"}, "ResponseMetadata": {"HTTPStatusCode": 403}}, "PutObject")
        content = Body.read() if hasattr(Body, "read") else Body
        if len(content) != ContentLength:
            raise AssertionError("unexpected content length")
        self.objects[(Bucket, Key)] = {"body": content, "metadata": Metadata, "version": "version-1"}
        return {"VersionId": "version-1"}

    def head_object(self, Bucket, Key, VersionId=None):
        value = self.objects.get((Bucket, Key))
        if value is None:
            from botocore.exceptions import ClientError
            raise ClientError({"Error": {"Code": "NoSuchKey"}}, "HeadObject")
        return {"ContentLength": len(value["body"]), "Metadata": value["metadata"], "VersionId": value["version"]}

    def get_object(self, Bucket, Key, Range=None, VersionId=None):
        value = self.objects[(Bucket, Key)]["body"]
        if Range:
            start, end = (int(part) for part in Range.removeprefix("bytes=").split("-"))
            value = value[start:end + 1]
        return {"Body": FakeBody(value)}

    def delete_object(self, Bucket, Key, VersionId=None):
        self.objects.pop((Bucket, Key), None)


class MinioAdapterTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.s3 = FakeS3()
        self.key = str(uuid.uuid4()) + ".png"
        self.upload_id = str(uuid.uuid4())
        self.operation_id = str(uuid.uuid4())
        self.content = b"binary\x00media\xff"
        self.checksum = hashlib.sha256(self.content).hexdigest()
        self.settings = patch.dict(os.environ, {"ARTIO_S3_ENDPOINT": "https://minio.internal", "ARTIO_S3_BUCKET": "artio-media",
                                                "ARTIO_S3_PREFIX": "products/media", "ARTIO_STORAGE_MODE": "external"})
        self.settings.start()

    def tearDown(self):
        self.settings.stop()
        self.temporary.cleanup()

    def put_payload(self):
        return {"key": self.key, "uploadId": self.upload_id, "chunkIndex": 0, "totalChunks": 1,
                "chunkBase64": base64.b64encode(self.content).decode("ascii"), "sha256": self.checksum,
                "complete": True, "placementRole": "PRIMARY"}

    def invoke(self, operation, payload, operation_id=None):
        return server.invoke(operation, payload, operation_id or str(uuid.uuid4()), self.root, self.s3)

    def test_put_head_get_delete_uses_scoped_opaque_object_and_version(self):
        result = self.invoke("put", self.put_payload(), self.operation_id)
        self.assertEqual({"sha256": self.checksum, "versionId": "version-1"}, result)
        self.assertEqual("products/media/" + self.key, next(iter(self.s3.objects))[1])
        self.assertEqual(self.checksum, self.invoke("head", {"key": self.key, "placementRole": "PRIMARY"})["sha256"])
        retrieved = self.invoke("get", {"key": self.key, "chunkIndex": 0, "placementRole": "PRIMARY"})
        self.assertEqual(self.content, base64.b64decode(retrieved["chunkBase64"]))
        self.assertEqual("version-1", retrieved["versionId"])
        tombstone = str(uuid.uuid4())
        deleted = self.invoke("delete", {"key": self.key, "tombstoneId": tombstone,
                                          "placementRole": "PRIMARY"}, str(uuid.uuid4()))
        self.assertEqual(tombstone, deleted["tombstoneId"])
        self.assertFalse(self.s3.objects)
        with self.assertRaises(server.StorageError) as conflict:
            self.invoke("delete", {"key": self.key, "tombstoneId": str(uuid.uuid4()),
                                    "placementRole": "PRIMARY"}, str(uuid.uuid4()))
        self.assertEqual("tombstone_conflict", conflict.exception.code)

    def test_idempotency_conflict_and_checksum_validation(self):
        payload = self.put_payload()
        self.invoke("put", payload, self.operation_id)
        changed = dict(payload, chunkBase64=base64.b64encode(b"different").decode("ascii"))
        with self.assertRaises(server.StorageError) as conflict:
            self.invoke("put", changed, self.operation_id)
        self.assertEqual("idempotency_conflict", conflict.exception.code)

        other = dict(payload, key=str(uuid.uuid4()) + ".png", uploadId=str(uuid.uuid4()), sha256="0" * 64)
        with self.assertRaises(server.StorageError) as mismatch:
            self.invoke("put", other)
        self.assertEqual("object_checksum_mismatch", mismatch.exception.code)

    def test_key_and_bucket_prefix_are_constrained(self):
        with self.assertRaises(server.StorageError):
            self.invoke("get", {"key": "../escape", "chunkIndex": 0})
        with patch.dict(os.environ, {"ARTIO_S3_PREFIX": "../other"}):
            with self.assertRaises(server.StorageError) as failure:
                self.invoke("get", {"key": self.key, "chunkIndex": 0, "placementRole": "PRIMARY"})
        self.assertEqual("invalid_prefix", failure.exception.code)

    def test_put_does_not_overwrite_an_untracked_provider_object(self):
        self.s3.objects[("artio-media", "products/media/" + self.key)] = {
            "body": b"x" * len(self.content), "metadata": {"artio-sha256": self.checksum},
            "version": "existing-version",
        }
        with self.assertRaises(server.StorageError) as failure:
            self.invoke("put", self.put_payload())
        self.assertEqual("object_version_conflict", failure.exception.code)
        self.assertEqual(b"x" * len(self.content), self.s3.objects[("artio-media", "products/media/" + self.key)]["body"])

    def test_storage_endpoint_cannot_be_changed_while_objects_are_placed(self):
        self.invoke("put", self.put_payload())
        with patch.dict(os.environ, {"ARTIO_S3_ENDPOINT": "https://different-minio.internal"}):
            with self.assertRaises(server.StorageError) as failure:
                self.invoke("head", {"key": self.key, "placementRole": "PRIMARY"})
        self.assertEqual("storage_identity_conflict", failure.exception.code)

    def test_diagnostic_proves_scoped_round_trip_and_removes_its_object(self):
        self.assertEqual("ready", server.diagnose(self.s3)["status"])
        self.assertEqual({}, self.s3.objects)

    def test_credential_values_are_loaded_from_secret_file(self):
        with self.assertRaises(server.StorageError) as failure:
            server._client()
        self.assertEqual("credentials_unavailable", failure.exception.code)
        with tempfile.TemporaryDirectory() as directory:
            secret = Path(directory) / "credentials.json"
            secret.write_text(json.dumps({"accessKeyId": "scoped", "secretAccessKey": "not-logged"}), encoding="utf-8")
            with patch.dict(os.environ, {"ARTIO_S3_CREDENTIALS_FILE": str(secret), "ARTIO_S3_ENDPOINT": "https://minio.internal"}):
                client = server._client()
            self.assertEqual("s3", client.meta.service_model.service_name)

    def test_rejects_plaintext_http_and_endpoint_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            secret = Path(directory) / "credentials.json"
            secret.write_text(json.dumps({"accessKeyId": "scoped", "secretAccessKey": "not-logged"}), encoding="utf-8")
            for endpoint in ("http://minio.internal", "https://user:password@minio.internal"):
                with patch.dict(os.environ, {"ARTIO_S3_CREDENTIALS_FILE": str(secret), "ARTIO_S3_ENDPOINT": endpoint}):
                    with self.assertRaises(server.StorageError) as failure:
                        server._client()
                self.assertEqual("invalid_endpoint", failure.exception.code)

    def test_managed_mode_uses_only_the_injected_private_service_and_secret_files(self):
        with tempfile.TemporaryDirectory() as directory:
            access_path = Path(directory) / "runtime-access-key"
            secret_path = Path(directory) / "runtime-secret-key"
            access_path.write_text("managed-access-key", encoding="utf-8")
            secret_path.write_text("managed-secret-key", encoding="utf-8")
            with patch.object(server, "MANAGED_ACCESS_KEY_FILE", access_path), \
                    patch.object(server, "MANAGED_SECRET_KEY_FILE", secret_path), \
                    patch.dict(os.environ, {"ARTIO_STORAGE_MODE": "managed",
                                            "ARTIO_EXTENSION_SERVICE_MINIO_HOST": "artio-minio-test",
                                            "ARTIO_EXTENSION_SERVICE_MINIO_PORT": "9000"}, clear=False):
                client = server._client()
                self.assertEqual("http://artio-minio-test:9000", client._endpoint.host)
                self.assertEqual((server.MANAGED_BUCKET, server.MANAGED_PREFIX), server._bucket())

    def test_managed_mode_rejects_uninjected_endpoints(self):
        with patch.dict(os.environ, {"ARTIO_STORAGE_MODE": "managed",
                                    "ARTIO_EXTENSION_SERVICE_MINIO_HOST": "127.0.0.1",
                                    "ARTIO_EXTENSION_SERVICE_MINIO_PORT": "9001"}, clear=False):
            with self.assertRaises(server.StorageError) as failure:
                server._client()
        self.assertEqual("managed_storage_unavailable", failure.exception.code)

    def test_maps_provider_authorization_status_to_denial_without_exposing_provider_errors(self):
        from botocore.exceptions import ClientError

        failure = ClientError(
            {
                "Error": {"Code": "MinioPolicyDenied", "Message": "sensitive provider detail"},
                "ResponseMetadata": {"HTTPStatusCode": 403},
            },
            "PutObject",
        )
        with self.assertRaises(server.StorageError) as denied:
            server._s3_call(lambda: (_ for _ in ()).throw(failure))
        self.assertEqual("provider_denied", denied.exception.code)
        self.assertNotIn("sensitive provider detail", str(denied.exception))


if __name__ == "__main__":
    unittest.main()
