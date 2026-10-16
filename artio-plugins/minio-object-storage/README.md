# Artio MinIO object-storage adapter

This is an Artio Docker plugin implementing the existing `storage.object.v1` capability. Core remains responsible for authorization, operation identity, binding selection, placement, media policy and business metadata. The adapter only maps the authenticated plugin protocol to S3-compatible object operations in the configured bucket and prefix.

The plugin supports two modes. **Managed** starts a private MinIO sidecar on the plugin's isolated Docker network, stores its data in a per-instance named volume, and runs a bounded one-shot provisioner before the adapter starts. Artio generates the server root user/password and scoped runtime access/secret keys, encrypts them in its existing plugin configuration store, and delivers them only through declared private secret files. The server and provisioner receive the root credentials; the adapter receives only its scoped runtime identity. The provisioner creates the private `artio-objects` bucket, enables versioning, creates an inline prefix-scoped service-account policy, and verifies both allowed and denied S3 requests. For runtime credential rotation, disable the instance, use the `runtime` generated-secret rotation action, reinstall and enable it: the pre-start provisioner creates and verifies the replacement while the adapter is stopped; a separate post-health job then revokes only superseded `artio-storage` identities. If startup or health fails, the old identity remains available for a safe retry. Existing bucket and object data are retained.

**External** connects to an administrator-approved HTTPS endpoint. The bucket and prefix must already exist and be private; provide a scoped identity limited to the configured prefix. The `s3CredentialsJson` field is write-only and delivered as `/run/secrets/s3-credentials.json`, never as an environment variable. Its JSON shape is `{"accessKeyId":"…","secretAccessKey":"…"}`. Core's capability authorization remains independent from S3 credentials in both modes.

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

The regular tests use a fake S3 client. The opt-in real-server acceptance builds the source-locked MinIO Community server, adapter, and provisioner, creates a uniquely named isolated Docker network and protected temporary secret volumes, and verifies scoped provisioning, managed-mode diagnostics, versioned S3 round-trip, checksum, tombstone, invalid-credential behavior, and staged runtime-identity rotation including failure retention and post-health cleanup. Root credentials are mounted only into MinIO and the short-lived provisioner; the adapter test receives a separate runtime-only secret volume. Run it from the Core repository:

```powershell
.\artio-plugins\minio-object-storage\verify-real-minio.ps1
```

The local acceptance is development evidence only. It verifies the server and managed provisioning containers outside the packaged Core controller; it does not prove a signed Marketplace release, install/activation through production Core, product-media persistence across a full Core restart, backup/restore, or production readiness. The image references in the manifest are publication templates and are deliberately not usable release digests.

## Upstream and release status

The MinIO Community source is pinned and built separately in `minio-server.Dockerfile`; its source, toolchain and base-image lock are documented in `MINIO_BUILD.md`. At the 2026-10-16 review, the [official Community repository](https://github.com/minio/minio) was archived and its latest tagged Community release was [`RELEASE.2025-10-15T17-29-55Z`](https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z). Community MinIO is [AGPL-3.0](https://github.com/minio/minio/blob/master/LICENSE). The official [GHSA-9c4q-hq6p-c237](https://github.com/minio/minio/security/advisories/GHSA-9c4q-hq6p-c237) documents an unpatched high-severity upload signature bypass affecting Community versions; [GHSA-hv4r-mvr4-25vw](https://github.com/minio/minio/security/advisories/GHSA-hv4r-mvr4-25vw) documents a related bypass and recommends proxy-level rejection of unsigned-trailer uploads. The plugin has no such proxy and does not treat that workaround as a release fix. This MinIO Community bundle is therefore blocked from Marketplace approval/publication. The provisioner pins the official `madmin-go/v3 v3.0.109` SDK used by that MinIO release and `minio-go/v7 v7.0.91`; the admin SDK is AGPL-3.0-or-later and its notice is in `provisioner/THIRD_PARTY_NOTICES.md`. Separate containers do not resolve license obligations. Any future candidate needs a maintained and legally approved server choice, complete dependency license inventory, corresponding-source/build materials, verified immutable image digests, successful scans, and a signed Marketplace release descriptor. Do not substitute AIStor or an unverified third-party image.
