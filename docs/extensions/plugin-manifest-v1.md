# Artio Docker Extension Manifest v1

## Current implementation

`artio-extensions` contains the Core-owned manifest model and a fail-closed structural validator. The admin endpoint `POST /api/v1/admin/extensions/validate` runs preflight validation only. It does not pull, start, or trust an image by virtue of validating its metadata. Trusted registry hosts are configured with `ARTIO_EXTENSION_TRUSTED_REGISTRIES`; the default is empty, so no registry is trusted by default. Images must use a sha256 digest.

The validator checks manifest version, identifier and semantic-version shape, Core compatibility bounds, image pinning and registry allowlist, geographic availability, supported permission and extension-point identifiers, dependency presence, configuration field limits and supported form metadata, and bounded in-container health/diagnostics paths. Manifest values are untrusted metadata; they do not grant Core authorization.

Every manifest exposes `geographicAvailability`: `GLOBAL` must have an empty `countries` array; `COUNTRY_SPECIFIC` must list one or more uppercase ISO 3166-1 alpha-2 codes. Older source callers that construct the v1 model without this field map to explicit `GLOBAL` metadata. The manager API accepts `?country=CH` to filter discovery to global plugins and plugins listing that country, and the Backoffice shows the declared scope. This is discovery metadata only: it does not prevent an administrator from registering or installing a compatible plugin for an additional market.

Supported v1 extension points include `catalog.search.v1`. A catalog search implementation provides `index`, `remove`, and `search` operations using the documented `ProductSearchProvider` DTOs. Rebuildable providers also support `beginRebuild`, `commitRebuild`, and `abortRebuild`; `beginRebuild` returns a bounded `generation` identifier, Core passes that identifier on index and search requests, and commit/abort target only that generation. Search request data is in the `request` member and returns a `productIds` array; Core hydrates current catalog records and requested-locale → English → base text from PostgreSQL, so plugins do not become the source of catalog truth. Set `ARTIO_SEARCH_PROVIDER=extension` and `ARTIO_SEARCH_EXTENSION_PLUGIN_ID` to select the enabled plugin. OpenSearch remains available as an optional native adapter when extension mode is not selected.

### Multi-service plugin bundles

Manifest v1 may include an optional `services` array with zero to eight private sidecars. Each item has a lowercase service `id`, a digest-pinned `image` from a trusted registry, a container `port`, a bounded absolute `healthPath`, and an optional `configurationBindings` object. `configurationBindings` maps a container environment variable name (uppercase shell identifier) to a field key in the manifest's configuration schema. The top-level manifest may also declare `configurationBindings` for its primary image. Values are references to schema keys, never literals, so registry manifests do not contain passwords. Core passes only referenced values to the isolated controller at enable/credential rotation; configured secret values are decrypted just in time, transported over the authenticated private controller API, and injected into the container. Values are not placed in labels, logs, or audit events. The combined bound values are capped below 1 MiB, individual environment values at 10 KiB, and bound values must be scalar (JSON fields cannot be mapped).

The manifest's top-level `image` remains the plugin API/invocation service and existing single-image manifests continue to work unchanged. Service ids must be unique. A sidecar has no host port, host mount, caller-selected Docker options or direct public route; Core gives it the same restricted resource, filesystem, capability and private-network policy as the primary image. Sidecars do not receive the plugin invocation credential. When a stopped managed container's actual environment differs from its declared bindings, the controller recreates it before starting so saved settings take effect. It will not change a running container's environment.

The isolated controller pulls every bundle image before replacing a stopped installation. Enable starts all services and requires every declared health endpoint to pass; a failed start/readiness check stops the bundle. Disable, credential rotation, update and uninstall operate on all containers bearing the plugin's managed identity labels, and refuse to remove running or unmanaged containers. Invocation and the existing health API continue to target the top-level primary image. The v1 manifest does not yet describe inter-service dependency ordering or an authenticated Backoffice route/proxy for sidecar UIs; those capabilities must not be inferred from `services`.

## Persisted management API

`POST /api/v1/admin/extensions/register` validates and persists a plugin manifest without pulling or starting its image. `GET /api/v1/admin/extensions` and `GET /api/v1/admin/extensions/{pluginId}` return lifecycle/configuration metadata and the names of configured secrets, never secret values. `PUT /api/v1/admin/extensions/{pluginId}/configuration` validates submitted values against manifest field types, preserves already configured secrets omitted from the update, encrypts newly submitted secret values with the Core AES-GCM `EncryptionService`, and appends an audit event. `GET /api/v1/admin/extensions/{pluginId}/audit` is admin-only. V15 stores manifests/configuration and provides append-only audit rows. The API and PostgreSQL integration test covers register, valid/invalid configuration, encrypted secret persistence, omission-preserving updates and secret-free responses/audit.

Runtime dependency checks use the installed manifest version and lifecycle state: install requires every dependency to be installed and inside its declared inclusive version range; enable requires every dependency to be enabled; disable is rejected while another enabled extension depends on the target. Registration's structural validation only confirms dependency declarations refer to registered plugin IDs.

Docker install, start/readiness and stop failures mark the plugin `FAILED` and append a lifecycle failure audit containing only the exception class. A later install can retry a failed plugin. Dependency and invalid-state rejections do not mutate plugin state.

Backoffice exposes Install, Enable, Disable, Uninstall, Health, schema-backed configuration, credential rotation, update and rollback. Uninstall requires the extension to be stopped and not be an enabled dependency; it removes only a container carrying the runtime's managed identity labels, redacts stored configuration/secrets, hides the plugin from the active registry, and preserves immutable release/audit history. Re-registering the same identifier remains blocked by the retained tombstone. The Activity view displays the latest 100 audit events with secret-free details. When an extension is enabled, an administrator can make an explicit bounded diagnostic invocation against a manifest-declared extension point; the UI warns that it sends a real request and recommends a non-destructive operation.

## Example

```json
{
  "manifestVersion": "1",
  "id": "payments.example",
  "name": "Example Payments",
  "vendor": "Example Ltd",
  "version": "1.2.0",
  "description": "Payment provider adapter",
  "minimumCoreVersion": "0.1.0",
  "maximumCoreVersion": "0.9.0",
  "image": "ghcr.io/example/payments@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "dependencies": [],
  "permissions": ["payment.process"],
  "configuration": {
    "apiKey": {
      "type": "secret",
      "required": true,
      "secret": true,
      "label": "API key",
      "description": "Provider credential",
      "format": null,
      "options": [],
      "minimumLength": null,
      "maximumLength": null,
      "minimum": null,
      "maximum": null
    }
  },
  "extensionPoints": ["payment.provider.v1"],
  "healthPath": "/health",
  "diagnosticsPath": "/diagnostics",
  "geographicAvailability": {
    "scope": "GLOBAL",
    "countries": []
  }
}
```

## Security and remaining lifecycle work

Registration, configuration, secret encryption and audit are admin-only. The optional Docker Engine adapter (`ARTIO_EXTENSION_DOCKER_ENABLED=true`) supports digest-pinned pulls, restricted non-root/read-only containers on the internal `artio-extensions` network, lifecycle endpoints (`install`, `enable`, `disable`), readiness polling for up to 30 seconds, bounded health probes and the initial v1 invocation contract. A per-process circuit breaker opens after five final failures for 30 seconds and allows one recovery probe. Core retries one `502`, `503` or `504` response within the original invocation deadline, preserving the same idempotency key; plugins must deduplicate that key because a network/provider outcome may be ambiguous. Standard Compose runs the adapter in a separate `extension-controller`; only that service mounts the Docker socket, the controller port is not published, and Core uses an authenticated internal HTTP API. Set `ARTIO_EXTENSION_DOCKER_ENABLED=false` to disable Core lifecycle calls. V24 persists a positive credential generation per plugin. The HMAC credential for generation 1 retains the pre-V24 derivation, avoiding an implicit credential change on upgrade; admin rotation advances the generation and replaces the existing managed container, restarting it only when it was enabled. Rotation progress is persisted as `ROTATING` with the previous lifecycle state so an interrupted operation can be retried; secrets and token values are not returned or audited. V25 records immutable manifest/image releases and supports install/update/rollback for stopped plugins. Each update requires a higher SemVer, a trusted digest-pinned image, compatible configuration, and a successful pull before removing the old stopped container. Rollback selects the preceding recorded release. Both operations leave the plugin stopped as `INSTALLED`; re-enabling is an explicit action. Secret fields removed from a replacement schema are purged from encrypted configuration. Backoffice exposes rotation, update, rollback, recent activity and explicit diagnostic invocation. `scripts/verify-extension-runtime.ps1` exercises a runnable reference image through install, invocation, update, rollback and invocation of the restored image. Cross-instance circuit-breaker state is not implemented; the current per-process breaker is a residual limitation when global suppression is required.

## Core-to-plugin invocation v1

Enabled plugin containers receive `ARTIO_EXTENSION_API_VERSION=1` and a plugin-scoped `ARTIO_EXTENSION_TOKEN`. Core derives that bearer token with HMAC-SHA256 from the application encryption key and the plugin identity; it is stable across container restart and changes if the configured encryption key changes. Tokens are never returned from management APIs. This uses bearer authentication over the isolated Docker network, not TLS; the network and Docker daemon remain trusted infrastructure.

Core sends `POST /v1/invoke` with `X-Artio-Extension-Api: 1`, `Authorization: Bearer <token>`, `Content-Type: application/json`, and `Idempotency-Key: <UUID>`. The JSON envelope is `{ "protocolVersion": 1, "extensionPoint": "payment.provider.v1", "operation": "authorize", "idempotencyKey": "<same UUID>", "configuration": { ... }, "payload": { ... } }`. The extension point must be declared by the manifest. Configuration contains the plugin's public settings and its decrypted secrets; Core decrypts them just in time and sends them only in the authenticated request body. Configuration plus payload and envelope are limited to 1 MiB total; the response is limited to 1 MiB. Calls have a caller-supplied deadline from 100 ms to 30 seconds. Core retries at most once for `502`, `503` or `504`, within the original end-to-end deadline and with the identical UUID idempotency key. It returns 504 on deadline, 503 when the container cannot be reached, and 502 for plugin errors or invalid JSON. It records successful management invocations in audit metadata without storing configuration or request/response bodies. The plugin must implement idempotency for retryable operations; Core does not claim exactly-once execution. An admin-only management invoke route is provided for diagnostics; product workflows should call the runtime port from Core services, never expose this route to storefront users.

Docker daemon access is a host-level trust boundary. The API exposes no arbitrary command/shell facility. Managed extension containers receive no host mounts, published ports, added capabilities, or direct egress; they have a read-only root filesystem, non-root UID, memory/CPU/PID caps and a private internal network. Admin diagnostics can invoke the bounded authenticated contract; application workflows can depend on the Core-owned runtime port. The real reference-container lifecycle is covered by the Phase 2 verification script. A deployment-specific security review, image signature/provenance verification and cross-instance breaker state remain follow-up hardening.
