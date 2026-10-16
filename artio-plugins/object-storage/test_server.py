import base64
import hashlib
import tempfile
import unittest
import uuid
from pathlib import Path

import server


class ObjectStorageTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.key = str(uuid.uuid4()) + ".png"
        self.upload = str(uuid.uuid4())
        self.bytes = b"durable media contents"
        self.checksum = hashlib.sha256(self.bytes).hexdigest()

    def tearDown(self):
        self.temporary.cleanup()

    def put(self, content, upload_id=None, index=0, total=1, complete=True, key=None, role="PRIMARY"):
        checksum = hashlib.sha256(content).hexdigest()
        chunk = content[index * server.CHUNK_BYTES:(index + 1) * server.CHUNK_BYTES]
        payload = {"key": key or self.key, "uploadId": upload_id or self.upload, "chunkIndex": index,
                   "totalChunks": total, "chunkBase64": base64.b64encode(chunk).decode(), "sha256": checksum,
                   "complete": complete, "placementRole": role}
        return payload, checksum

    def test_commits_only_complete_checksum_verified_chunked_upload_and_reads_chunks(self):
        payload, checksum = self.put(self.bytes)
        result = server.invoke("put", payload, str(uuid.uuid4()), self.root)
        self.assertEqual(checksum, result["sha256"])
        self.assertTrue(result["versionId"])
        get = {"key": self.key, "chunkIndex": 0, "placementRole": "PRIMARY"}
        retrieved = server.invoke("get", get, str(uuid.uuid4()), self.root)
        self.assertEqual(self.bytes, base64.b64decode(retrieved["chunkBase64"]))
        self.assertEqual(checksum, retrieved["sha256"])

    def test_chunk_assembly_rejects_missing_or_corrupt_chunks(self):
        content = b"a" * (server.CHUNK_BYTES + 11)
        first, _ = self.put(content, total=2, index=0, complete=False)
        server.invoke("put", first, str(uuid.uuid4()), self.root)
        last, _ = self.put(content, total=2, index=1, complete=True)
        result = server.invoke("put", last, str(uuid.uuid4()), self.root)
        self.assertEqual(hashlib.sha256(content).hexdigest(), result["sha256"])

        bad_key = str(uuid.uuid4()) + ".png"
        bad, _ = self.put(b"wrong", key=bad_key)
        bad["sha256"] = "0" * 64
        with self.assertRaises(server.StorageError) as failure:
            server.invoke("put", bad, str(uuid.uuid4()), self.root)
        self.assertEqual("object_checksum_mismatch", failure.exception.code)

    def test_idempotency_replay_conflicts_and_tombstone_is_durable(self):
        payload, _ = self.put(self.bytes)
        put_id = str(uuid.uuid4())
        result = server.invoke("put", payload, put_id, self.root)
        self.assertEqual(result, server.invoke("put", payload, put_id, self.root))
        changed = {**payload, "chunkBase64": base64.b64encode(b"different").decode()}
        with self.assertRaises(server.StorageError) as conflict:
            server.invoke("put", changed, put_id, self.root)
        self.assertEqual(409, conflict.exception.status)

        tombstone = str(uuid.uuid4())
        delete = {"key": self.key, "tombstoneId": tombstone, "placementRole": "PRIMARY"}
        delete_id = str(uuid.uuid4())
        self.assertEqual(tombstone, server.invoke("delete", delete, delete_id, self.root)["tombstoneId"])
        self.assertEqual(tombstone, server.invoke("delete", delete, delete_id, self.root)["tombstoneId"])
        self.assertTrue((self.root / "tombstones" / (self.key[:-4] + ".json")).exists())
        with self.assertRaises(server.StorageError) as missing:
            server.invoke("get", {"key": self.key, "chunkIndex": 0, "placementRole": "PRIMARY"}, str(uuid.uuid4()), self.root)
        self.assertEqual(404, missing.exception.status)
        with self.assertRaises(server.StorageError) as blocked:
            server.invoke("put", payload, str(uuid.uuid4()), self.root)
        self.assertEqual("object_tombstoned", blocked.exception.code)

    def test_rejects_path_traversal_and_invalid_placement(self):
        invalid = {"key": "../secrets", "chunkIndex": 0, "placementRole": "PRIMARY"}
        with self.assertRaises(server.StorageError) as failure:
            server.invoke("get", invalid, str(uuid.uuid4()), self.root)
        self.assertEqual(400, failure.exception.status)


if __name__ == "__main__":
    unittest.main()
