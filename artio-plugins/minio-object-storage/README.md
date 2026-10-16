# Artio MinIO object-storage adapter

This is an Artio Docker plugin implementing the existing `storage.object.v1` capability. Core remains responsible for authorization, operation identity, binding selection, placement, media policy and business metadata. The adapter only maps the authenticated plugin protocol to S3-compatible object operations in the configured bucket and prefix.

The current adapter configuration connects to an administrator-approved **HTTPS external endpoint**. The bucket and prefix must already exist and be private; provide a scoped identity limited to the configured prefix. The `s3CredentialsJson` field is a write-only Artio secret delivered as `/run/secrets/s3-credentials.json`, never as an environment variable. Its JSON shape is `{"accessKeyId":"…","secretAccessKey":"…"}`. The adapter does not create buckets, policies, identities or server configuration. Managed MinIO installation and provisioning are not implemented by this adapter release.

## Storage behavior

- Opaque object keys are restricted to Artio's current JPEG/PNG media key contract. Provider keys are always `prefix/key`; request data cannot select a bucket, endpoint or arbitrary path.
- The adapter accepts the existing 512 KiB chunks, at most 20 chunks (10 MiB total), validates the full SHA-256 before provider write, and assembles through bounded temporary files. It uses ranged reads and verifies each returned range length. Core verifies the whole-object checksum.
- A successful completion returns the MinIO version ID when bucket versioning is enabled. The provider's ETag is never treated as a checksum. A sidecar record under the instance's persistent `/data` volume preserves the version and durable operation/tombstone identity.
- The endpoint, bucket and prefix identity is pinned in the persistent volume once objects are placed. Reconfiguration to another storage location is rejected while active objects or tombstones exist, so an administrator cannot silently strand historical bytes; perform an explicit migration to another plugin instance first.
- Retries use Core's idempotency key and a canonical request fingerprint. A key collision with provider bytes that do not match the requested length and SHA-256 is rejected without overwriting those bytes. An uncertain PUT is reconciled by exact key, length and strong checksum metadata.
- `/health` reports process liveness. `/diagnostics` performs a scoped temporary write/read/checksum/delete round-trip and removes only its own diagnostic object.

## Local checks

Run the adapter tests from the Core repository:

```powershell
python -m unittest discover -s artio-plugins/minio-object-storage -p 'test_*.py' -v
```

These tests use a fake S3 client. They do not prove compatibility with a real MinIO server, Docker networking, controller installation, Marketplace approval, product-media persistence across a real restart, backup/restore, or managed provisioning. The image reference in the manifest is a publication template and is deliberately not a usable release digest.

## Upstream and release status

The MinIO server is not bundled in this adapter. At the 2026-10-16 implementation review, the [official Community repository](https://github.com/minio/minio) was archived and the then-latest tagged Community source release was [`RELEASE.2025-10-15T17-29-55Z`](https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z); confirm upstream status, security advisories, source commit and license again before building or publishing any server image. Community MinIO source is [AGPL-3.0](https://github.com/minio/minio/blob/master/LICENSE). Do not substitute AIStor or an unverified third-party image. Any distributable server image needs reproducible source/build provenance, corresponding-source and license notices, and an explicitly approved release descriptor.
