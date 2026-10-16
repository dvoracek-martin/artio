# Artio Object Storage reference plugin

This isolated reference plugin implements `storage.object.v1`. The Core routes each object operation to one primary and the configured replicas, encrypts read-result snapshots at rest, verifies whole-object SHA-256, and retains immutable target identities and provider versions. The plugin keeps objects, resumable upload chunks, idempotency records and deletion tombstones under its persistent `/data` volume.

The Core sends 512 KiB `put` chunks. The final chunk is committed only after the plugin has every chunk and validates the whole-object digest. `get` returns one bounded chunk; `head` returns object metadata. `delete` writes a durable tombstone before removing bytes, so a retry cannot resurrect a deleted key. Every call requires the canonical Core idempotency UUID and supports deterministic replay; reusing the same key with different content returns a conflict.

`server.py` uses only the Python standard library. Run its local tests with `python -m unittest -v` from this directory. Build the development image with `docker build -t artio/object-storage:dev .`; the installable v2 manifest remains a template until an immutable registry image digest has been published and trusted by the Core.

The plugin has no database connection or public route. Its controller endpoint accepts only the authenticated Artio runtime envelope, and persistent storage is limited to the plugin-owned `/data` mount.
