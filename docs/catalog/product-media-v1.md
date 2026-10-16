# Product media v1

Product images use PostgreSQL metadata plus the `MediaStorage` port. The default Compose runtime stores image bytes under `/var/lib/artio/media` on the persistent `artio-catalog-media` volume. Local development defaults to `./var/artio/media`; deployments can replace the filesystem adapter with another implementation of the same port. Multi-host API replicas must share a storage backend before serving media across hosts.

Each product can hold up to 100 images; writes lock the parent product row so simultaneous uploads cannot exceed that cap or assign the same next position. Only JPEG and PNG are accepted. The server detects the actual decoded image format instead of trusting a filename or request `Content-Type`, limits uploads to 10 MiB, dimensions to 10,000 pixels per side and 20 million total pixels, decodes and re-encodes images to strip untrusted metadata, and gives each stored object an opaque random key. SVG, animated formats and arbitrary files are rejected. Stored files are never placed in the container writable layer or served from a user-controlled path.

## Optional S3-compatible storage

Set `ARTIO_CATALOG_MEDIA_STORAGE=s3` to select the S3 adapter. The default remains `local`; the Backoffice uses the same upload API in both modes. Configure an existing private bucket and credentials on the API server, never in the browser:

| Environment variable | Purpose / default |
| --- | --- |
| `ARTIO_CATALOG_MEDIA_S3_BUCKET` | Required existing private bucket |
| `ARTIO_CATALOG_MEDIA_S3_REGION` | Signing region, default `us-east-1` |
| `ARTIO_CATALOG_MEDIA_S3_ENDPOINT` | Optional HTTP(S) endpoint for S3-compatible providers; blank uses AWS |
| `ARTIO_CATALOG_MEDIA_S3_PATH_STYLE` | Default `true` for compatible providers; can be `false` for AWS virtual host addressing |
| `ARTIO_CATALOG_MEDIA_S3_PREFIX` | Object namespace, default `products` |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` | Standard AWS credentials; the SDK also supports its default profile/workload credential chain |

The SDK signs requests and handles authentication. The service requires `s3:PutObject`, `s3:GetObject` and `s3:DeleteObject` on the configured prefix. It does not create buckets, set public ACLs or expose signed storage credentials to clients. Public catalog images continue to be served through Artio's content endpoint. Use HTTPS for a remote object-storage endpoint. The adapter sets bounded connection/request timeouts and uses required-only checksums for S3-compatible implementations. [AWS SDK endpoint configuration](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/endpoint-config.html) describes custom endpoints.

For Compose, export the variables above (or set them in your uncommitted `.env`) and add the supplied overlay:

```shell
docker compose -f artio-docker/docker-compose.yml -f artio-docker/docker-compose.media-s3.yml up -d --build
```

The overlay uses explicit access keys. Workload-role deployments can provide the same media configuration and the SDK credential chain without that overlay. Existing filesystem objects are not automatically migrated when switching adapters: copy them to the configured bucket/prefix with their original keys before switching, and retain matching database backups. Back up the object bucket together with PostgreSQL.

## Media routes

- `GET /api/v1/products/{productId}/media` — ordered media metadata and content URLs; public read.
- `GET /api/v1/products/{productId}/media/{mediaId}/content` — validated image bytes; public read, `nosniff`, revalidated cache.
- `POST /api/v1/products/{productId}/media` — multipart `file` plus optional `altText`; ADMIN/SUPERADMIN.
- `PUT /api/v1/products/{productId}/media/{mediaId}` — replace image bytes while retaining the media identity and position; ADMIN/SUPERADMIN.
- `PUT /api/v1/products/{productId}/media/order` — `{ "mediaIds": ["…"] }`; list must contain every media ID exactly once; ADMIN/SUPERADMIN.
- `DELETE /api/v1/products/{productId}/media/{mediaId}` — remove the association; blob deletion runs after database commit; ADMIN/SUPERADMIN.

Image bytes are stored before a metadata insert and cleaned up if that transaction rolls back. Replacement writes a new object then switches metadata transactionally; the old object is removed after commit. A failed post-commit file cleanup is logged and can leave an orphan file, but never a database reference to a missing image. The local storage adapter prevents path traversal and writes through an atomic temporary file.

Backoffice Products has a per-product media manager for upload, replacement, alt text, order and deletion. Storefront product cards show the first ordered public image and its alt text; products without images use the initials illustration. Public product media URLs do not contain secrets. Do not store private customer documents as catalog media.

The [product creation wizard](backoffice-product-wizard.md) also supports selecting multiple images, local previews, alternative text and ordering before creation. Images upload after the atomic product draft is saved; failed uploads can be retried or replaced without recreating the product. The standalone media manager keeps its required file input populated until upload succeeds so native browser validation does not block submission.

The v5.1 capability registry routes `storage.object.v1` through a Core-owned `PRIMARY_REPLICAS` plan. Set `artio.catalog.media.storage=plugins` to mediate media objects through enabled, explicitly bound storage plugin instances. The Core freezes every selected instance/release/configuration in `extension_operation_attempts` before the first call, deduplicates multiple matching bindings to one target per instance, calls the primary before replicas, and uses the same frozen target order and idempotency identity on retry or operator reconciliation. A write succeeds only after every target acknowledges the same SHA-256; reads verify the returned checksum and can recover from a failed primary by trying replicas. Deletes carry a stable tombstone UUID and are acknowledged only after all targets confirm. Plugins receive the `placementRole` field as `PRIMARY` or `REPLICA` and must make put/delete idempotent by the supplied operation idempotency key. The plugin protocol carries `{key, routingKey, uploadId, chunkIndex, totalChunks, chunkBase64, sha256, complete, placementRole}` for each bounded `put` chunk, `{key, routingKey, chunkIndex, placementRole}` for `get`, and `{key, routingKey, tombstoneId, placementRole}` for `delete`. Successful put chunks return `{sha256, versionId}` after finalization; get returns `{chunkBase64, chunkIndex, totalChunks, sha256, versionId}`; delete returns `{deleted: true, tombstoneId}`. `routingKey` points later chunks, reads and deletion at the original immutable primary/replica operation plan. The Core retains local and S3 adapters for installations that do not select plugin storage.

Plugin payloads stay within the authenticated runtime's 1 MiB bound: the Core sends media in 512 KiB chunks with an upload ID, chunk index/count, final-chunk marker and whole-object SHA-256. The plugin commits an object only after receiving every chunk and validating the complete checksum. Reads request bounded chunks and the Core verifies order, count, size and whole-object checksum before serving them. The Core records byte length, checksum, selected primary/release/config generation, replica identities and each provider version in `catalog_media_storage_objects`; failed cleanup remains marked `DELETE_PENDING` with the durable operation attempt available for reconciliation.

When plugin storage is selected, unbackfilled media remains readable from the configured source (`artio.catalog.media.migration.source=local|s3`, default `local`). Existing bytes are kept in the source throughout migration. Backoffice operators can copy media in bounded batches through `POST /api/v1/admin/catalog/media/storage-backfill?limit=50`; pass the returned `nextCursor` as `afterStorageKey` until `complete` is true. A failed object is reported by opaque key and error class, the cursor stays before that object for retry, and every successful destination copy is read back and SHA-256/length verified. The endpoint is limited to 100 objects per request and is protected by the `/api/v1/admin/**` ADMIN/SUPERADMIN policy. For S3 sources, keep the existing private bucket, region, endpoint, prefix and credentials configured while setting plugin storage as the destination. Source deletion is a separate, operator-controlled retention decision after the full copy is verified.

The source-only `artio-plugins/minio-object-storage` package implements this capability against an externally managed HTTPS S3-compatible endpoint. It reads its scoped access key from a `secretFileBindings` file and writes into an administrator-provisioned private bucket and prefix; it does not configure or provision MinIO. Its 9 adapter unit tests passed, and the opt-in acceptance built the source-locked Community MinIO server and exercised versioned put/read/checksum/tombstone plus invalid-credential denial in a real isolated Docker network (2/2 passed). See the [adapter contract and limits](../../artio-plugins/minio-object-storage/README.md) and [server source/build lock](../../artio-plugins/minio-object-storage/MINIO_BUILD.md). The manifest still has a publication placeholder; Marketplace approval, controller installation, Core media persistence/restart and backup/restore acceptance remain open. Community MinIO's archived AGPL-3.0 status is documented as a product/legal release decision, not a security-support commitment.

Primary/replica Core routing, adapter checksum/chunk checks and two real isolated Docker storage instances are covered by focused and live acceptance tests. `ObjectStorageDockerIntegrationTests.storesReadsAndTombstonesObjectsAcrossTwoIsolatedPluginInstances` verifies separate named volumes, object reads after replacing a container, and an isolated delete tombstone. A bounded local/S3 backfill path verifies destination read-back without deleting the source. `CatalogApiIntegrationTests.registersV2ReleasePersistsExplicitBindingAndRoutesOnlyEnabledDeclaredInstances` verifies pending tombstone recovery after simulated Core restart and Core-invoked backfill with source retention. The remaining cross-domain recovery/security matrix is tracked in P0-09.

## Verification and limits

Current Backoffice/draft/media checks and the distinction between unit-tested S3 requests and external-provider acceptance are recorded in [Backoffice product wizard verification](backoffice-product-wizard.md#verification-2026-10-14).

`gradlew.bat :artio-rest-api:integrationTest --tests io.artio.api.catalog.CatalogApiIntegrationTests.productMediaRequiresStaffAndSupportsSafeUploadReplacementOrderingAndDeletion --no-daemon` exited 0; the scenario was **skipped** because Docker Engine was unavailable, while the API unit suite passed and integration sources compiled. `ProductMediaServiceTests` passed image decode/re-encode, format rejection, dimension limit and filesystem key/read/delete checks. `npm run build` in `artio-backoffice` and `docker compose --env-file .env.example -f artio-docker/docker-compose.yml config --quiet` passed. The mounted-volume runtime and shared object-storage adapter are not deployment-tested here.
