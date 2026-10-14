# Product media v1

Product images use PostgreSQL metadata plus the `MediaStorage` port. The default Compose runtime stores image bytes under `/var/lib/artio/media` on the persistent `artio-catalog-media` volume. Local development defaults to `./var/artio/media`; deployments can replace the filesystem adapter with another implementation of the same port. Multi-host API replicas must share a storage backend before serving media across hosts.

Each product can hold up to 100 images; writes lock the parent product row so simultaneous uploads cannot exceed that cap or assign the same next position. Only JPEG and PNG are accepted. The server detects the actual decoded image format instead of trusting a filename or request `Content-Type`, limits uploads to 10 MiB, dimensions to 10,000 pixels per side and 20 million total pixels, decodes and re-encodes images to strip untrusted metadata, and gives each stored object an opaque random key. SVG, animated formats and arbitrary files are rejected. Stored files are never placed in the container writable layer or served from a user-controlled path.

## API

- `GET /api/v1/products/{productId}/media` — ordered media metadata and content URLs; public read.
- `GET /api/v1/products/{productId}/media/{mediaId}/content` — validated image bytes; public read, `nosniff`, revalidated cache.
- `POST /api/v1/products/{productId}/media` — multipart `file` plus optional `altText`; ADMIN/SUPERADMIN.
- `PUT /api/v1/products/{productId}/media/{mediaId}` — replace image bytes while retaining the media identity and position; ADMIN/SUPERADMIN.
- `PUT /api/v1/products/{productId}/media/order` — `{ "mediaIds": ["…"] }`; list must contain every media ID exactly once; ADMIN/SUPERADMIN.
- `DELETE /api/v1/products/{productId}/media/{mediaId}` — remove the association; blob deletion runs after database commit; ADMIN/SUPERADMIN.

Image bytes are stored before a metadata insert and cleaned up if that transaction rolls back. Replacement writes a new object then switches metadata transactionally; the old object is removed after commit. A failed post-commit file cleanup is logged and can leave an orphan file, but never a database reference to a missing image. The local storage adapter prevents path traversal and writes through an atomic temporary file.

Backoffice Products has a per-product media manager for upload, replacement, alt text, order and deletion. Storefront product cards show the first ordered public image and its alt text; products without images use the initials illustration. Public product media URLs do not contain secrets. Do not store private customer documents as catalog media.

## Verification and limits

`gradlew.bat :artio-rest-api:integrationTest --tests io.artio.api.catalog.CatalogApiIntegrationTests.productMediaRequiresStaffAndSupportsSafeUploadReplacementOrderingAndDeletion --no-daemon` exited 0; the scenario was **skipped** because Docker Engine was unavailable, while the API unit suite passed and integration sources compiled. `ProductMediaServiceTests` passed image decode/re-encode, format rejection, dimension limit and filesystem key/read/delete checks. `npm run build` in `artio-backoffice` and `docker compose --env-file .env.example -f artio-docker/docker-compose.yml config --quiet` passed. The mounted-volume runtime and shared object-storage adapter are not deployment-tested here.
