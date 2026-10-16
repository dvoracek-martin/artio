# Local plugin archive import v1

Superadmins with recent MFA can submit a registered plugin's Docker-save or OCI image-layout tar to
`POST /api/v1/admin/extensions/{pluginId}/installations/archive`. The request is multipart with an
`archive` part and an `Idempotency-Key`. Artio creates a durable installation job and writes the upload
to a private quarantine volume using a generated filename. Request bytes stream to disk; the API and
Backoffice never receive Docker access.

The default limits are a 2 GiB upload, 8 GiB expanded layer data, a 200:1 per-layer expansion ratio,
100,000 outer archive entries and 100,000 combined image-layer entries, 16 images, and 4 MiB per JSON document. These values can be
overridden with the `ARTIO_EXTENSION_ARCHIVE_MAX_*` settings. The inspector does not extract archive
paths. It rejects malformed or duplicate TAR entries, path traversal, links, devices, FIFOs, sparse
files, unsupported media types, invalid descriptor sizes, digest mismatches, and excessive expansion.
OCI layouts must exactly match the digest-pinned image inventory in the plugin manifest. Docker-save
bundles with both legacy Docker and OCI metadata are accepted only when both inventories point to the
same image; all OCI descriptors and content-addressed blobs are verified. Config digests and
uncompressed layer diff IDs are verified too.

Docker-save archives do not contain the registry manifest digest. Artio therefore matches their
repository tags to the registered image references, verifies each Docker config image ID and every
uncompressed layer diff ID, and records the archive SHA-256 separately. Such an inventory records no
verified registry manifest digest; it never labels a Docker config/image ID as that digest. OCI
archives record the verified manifest digests. Both formats map the plugin's declared image reference
to verified config digests and are imported under deterministic private Docker cache tags.
Those tags are local lookup keys derived from the registered image references; their names are not
OCI manifest or Docker config digests.

The worker rechecks the quarantined archive SHA-256 and plugin permission/security policy before
inspection and import. The existing durable installation lifecycle performs the import outside a
database transaction, records verified provenance, and removes the quarantine file after successful
installation. Failed jobs retain their archive for an explicit retry. New schema belongs to the one
current development snapshot, `V1__initial_schema.sql`; development does not add migration versions.
