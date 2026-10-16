"""Opt-in integration tests against a real, disposable MinIO instance."""

import base64
import hashlib
import json
import os
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest import mock

import boto3
from botocore.config import Config

import server


REQUIRED_ENV = (
    "ARTIO_MINIO_INTEGRATION_ENDPOINT",
    "ARTIO_MINIO_INTEGRATION_CREDENTIALS_FILE",
)
ENABLED = all(os.environ.get(key) for key in REQUIRED_ENV)


@unittest.skipUnless(ENABLED, "set ARTIO_MINIO_INTEGRATION_* to run against real MinIO")
class MinioProviderIntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.bucket = "artio-test-" + uuid.uuid4().hex
        cls.prefix = "acceptance/" + uuid.uuid4().hex
        cls.endpoint = os.environ["ARTIO_MINIO_INTEGRATION_ENDPOINT"]
        credentials = json.loads(
            Path(os.environ["ARTIO_MINIO_INTEGRATION_CREDENTIALS_FILE"]).read_text(
                encoding="utf-8"
            )
        )
        cls.client = boto3.client(
            "s3",
            endpoint_url=cls.endpoint,
            region_name="us-east-1",
            aws_access_key_id=credentials["accessKeyId"],
            aws_secret_access_key=credentials["secretAccessKey"],
            config=Config(
                signature_version="s3v4",
                s3={"addressing_style": "path"},
                connect_timeout=3,
                read_timeout=10,
                retries={"mode": "standard", "max_attempts": 1},
            ),
        )
        cls.client.create_bucket(Bucket=cls.bucket)
        cls.client.put_bucket_versioning(
            Bucket=cls.bucket, VersioningConfiguration={"Status": "Enabled"}
        )

    @classmethod
    def tearDownClass(cls):
        if not ENABLED:
            return
        continuation = None
        while True:
            request = {"Bucket": cls.bucket, "Prefix": cls.prefix}
            if continuation:
                request["ContinuationToken"] = continuation
            response = cls.client.list_objects_v2(**request)
            keys = [{"Key": item["Key"]} for item in response.get("Contents", [])]
            if keys:
                cls.client.delete_objects(Bucket=cls.bucket, Delete={"Objects": keys})
            if not response.get("IsTruncated"):
                break
            continuation = response["NextContinuationToken"]
        cls.client.delete_bucket(Bucket=cls.bucket)

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.environment = mock.patch.dict(
            os.environ,
            {"ARTIO_S3_ENDPOINT": self.endpoint, "ARTIO_S3_BUCKET": self.bucket, "ARTIO_S3_PREFIX": self.prefix},
        )
        self.environment.start()
        self.key = str(uuid.uuid4()) + ".png"
        self.upload_id = str(uuid.uuid4())
        self.content = b"real-minio-\x00binary-media-\xff" * 4096
        self.checksum = hashlib.sha256(self.content).hexdigest()

    def tearDown(self):
        self.environment.stop()
        self.directory.cleanup()

    def test_real_minio_round_trip_versioning_and_tombstone(self):
        total_chunks = (len(self.content) + server.CHUNK_BYTES - 1) // server.CHUNK_BYTES
        result = None
        for index in range(total_chunks):
            chunk = self.content[index * server.CHUNK_BYTES : (index + 1) * server.CHUNK_BYTES]
            payload = {
                "key": self.key,
                "uploadId": self.upload_id,
                "chunkIndex": index,
                "totalChunks": total_chunks,
                "chunkBase64": base64.b64encode(chunk).decode("ascii"),
                "sha256": self.checksum,
                "complete": index == total_chunks - 1,
                "placementRole": "PRIMARY",
            }
            result = server.invoke("put", payload, str(uuid.uuid4()), self.root, self.client)

        self.assertEqual(self.checksum, result["sha256"])
        self.assertTrue(result["versionId"])
        self.assertNotEqual("unversioned", result["versionId"])
        provider_key = self.prefix + "/" + self.key
        stored = self.client.get_object(Bucket=self.bucket, Key=provider_key)["Body"].read()
        self.assertEqual(self.content, stored)
        self.assertEqual(self.checksum, hashlib.sha256(stored).hexdigest())

        head = server.invoke(
            "head", {"key": self.key, "placementRole": "PRIMARY"}, str(uuid.uuid4()), self.root, self.client
        )
        self.assertEqual(len(self.content), head["byteLength"])
        retrieved = bytearray()
        for index in range(total_chunks):
            response = server.invoke(
                "get",
                {"key": self.key, "chunkIndex": index, "placementRole": "PRIMARY"},
                str(uuid.uuid4()),
                self.root,
                self.client,
            )
            retrieved.extend(base64.b64decode(response["chunkBase64"], validate=True))
        self.assertEqual(self.content, bytes(retrieved))
        self.assertEqual(self.checksum, hashlib.sha256(retrieved).hexdigest())

        tombstone_id = str(uuid.uuid4())
        deleted = server.invoke(
            "delete",
            {"key": self.key, "tombstoneId": tombstone_id, "placementRole": "PRIMARY"},
            str(uuid.uuid4()),
            self.root,
            self.client,
        )
        self.assertEqual(tombstone_id, deleted["tombstoneId"])
        with self.assertRaises(server.StorageError) as missing:
            server.invoke(
                "get",
                {"key": self.key, "chunkIndex": 0, "placementRole": "PRIMARY"},
                str(uuid.uuid4()),
                self.root,
                self.client,
            )
        self.assertEqual("object_tombstoned", missing.exception.code)

    def test_real_minio_denies_wrong_scoped_credentials(self):
        denied = boto3.client(
            "s3",
            endpoint_url=self.endpoint,
            region_name="us-east-1",
            aws_access_key_id="invalid-test-identity",
            aws_secret_access_key="invalid-test-secret-value",
            config=Config(s3={"addressing_style": "path"}, retries={"max_attempts": 1}),
        )
        payload = {
            "key": self.key,
            "uploadId": self.upload_id,
            "chunkIndex": 0,
            "totalChunks": 1,
            "chunkBase64": base64.b64encode(b"x").decode("ascii"),
            "sha256": hashlib.sha256(b"x").hexdigest(),
            "complete": True,
            "placementRole": "PRIMARY",
        }
        with self.assertRaises(server.StorageError) as failure:
            server.invoke("put", payload, str(uuid.uuid4()), self.root, denied)
        self.assertEqual("provider_denied", failure.exception.code)


if __name__ == "__main__":
    unittest.main(verbosity=2)
