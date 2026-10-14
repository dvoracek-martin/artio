# Switzerland distribution manifest v1

Artio Core packages the immutable `switzerland-v1` regional defaults at `artio-rest-api/src/main/resources/distributions/switzerland-v1.json`. The public, versioned read-only API exposes the installed catalog at `GET /api/v1/public/distributions` and one manifest at `GET /api/v1/public/distributions/{id}`. The unversioned `/api` alias remains available through the normal API versioning filter.

The manifest defines CH, CHF, `de-CH`, the `de`, `fr`, `it`, and `en` language set, and `Europe/Zurich`. These values are discoverable defaults; they do not silently overwrite merchant settings. Core validates each packaged manifest during application startup: supported schema version, unique versioned identifier, ISO country/currency, locale, IANA time zone, bounded unique languages, and unique well-formed plugin recommendation IDs.

The manifest currently recommends optional `payments.stripe` and `shipping.swiss-post` integrations. It does not contain images or digests because neither reference provider image has been built and published. Recommendations cannot be installed until an actual trusted, digest-pinned plugin manifest is available. The current API exposes catalog metadata only; setup-time plugin provisioning and per-plugin activation/configuration remain open in `docs/ROADMAP.md`.
