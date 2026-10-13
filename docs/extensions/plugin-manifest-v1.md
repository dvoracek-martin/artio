# Artio Docker Extension Manifest v1

## Current implementation

`artio-extensions` contains the Core-owned manifest model and a fail-closed structural validator. The admin endpoint `POST /api/v1/admin/extensions/validate` runs preflight validation only. It does not pull, start, or trust an image by virtue of validating its metadata. Trusted registry hosts are configured with `ARTIO_EXTENSION_TRUSTED_REGISTRIES`; the default is empty, so no registry is trusted by default. Images must use a sha256 digest.

The validator checks manifest version, identifier and semantic-version shape, Core compatibility bounds, image pinning and registry allowlist, supported permission and extension-point identifiers, dependency presence, configuration field limits and supported form metadata, and bounded in-container health/diagnostics paths. Manifest values are untrusted metadata; they do not grant Core authorization.

## Persisted management API

`POST /api/v1/admin/extensions/register` validates and persists a plugin manifest without pulling or starting its image. `GET /api/v1/admin/extensions` and `GET /api/v1/admin/extensions/{pluginId}` return lifecycle/configuration metadata and the names of configured secrets, never secret values. `PUT /api/v1/admin/extensions/{pluginId}/configuration` validates submitted values against manifest field types, preserves already configured secrets omitted from the update, encrypts newly submitted secret values with the Core AES-GCM `EncryptionService`, and appends an audit event. `GET /api/v1/admin/extensions/{pluginId}/audit` is admin-only. V15 stores manifests/configuration and provides append-only audit rows. The API and PostgreSQL integration test covers register, valid/invalid configuration, encrypted secret persistence, omission-preserving updates and secret-free responses/audit.

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
  "diagnosticsPath": "/diagnostics"
}
```

## Security and remaining lifecycle work

Registration, configuration, secret encryption and audit are admin-only. The optional Docker Engine adapter (`ARTIO_EXTENSION_DOCKER_ENABLED=true`) now supports digest-pinned pulls, restricted non-root/read-only containers on the internal `artio-extensions` network, lifecycle endpoints (`install`, `enable`, `disable`) and bounded health probes. The standard Compose setup does not mount the Docker socket; operators must explicitly include `artio-docker/docker-compose.extensions.yml` to enable the host-admin trust boundary. A runnable reference image, full readiness polling, invocation envelope/authentication/deadlines/circuit breaker, and safe update/rollback path remain required before Phase 2 can pass its exit gate.

Docker daemon access is a host-level trust boundary. The API exposes no arbitrary command/shell facility. Managed extension containers receive no host mounts, published ports, added capabilities, or direct egress; they have a read-only root filesystem, non-root UID, memory/CPU/PID caps and a private internal network. Current runtime endpoints do not execute plugin operations. Invocation support must use a bounded, authenticated contract.
