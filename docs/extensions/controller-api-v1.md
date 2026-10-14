# Extension controller API v1

The Core API calls an isolated lifecycle controller when the Docker extension runtime is enabled. The controller is a separate Spring Boot entry point built from the same source module; the REST API and controller are packaged as distinct executable JARs. Docker-java is compile-only for Core and is included only in the controller JAR. The public REST API has no Docker socket mount.

## Network and credentials

The default Compose topology attaches `rest-api` and `extension-controller` to the private `artio-extension-control` network. The controller also joins the private `artio-extensions` network so it can inspect and probe managed plugin containers. No controller port is published to the host, frontends, Kafka, or PostgreSQL. The REST API is not attached to `artio-extensions`; frontends, Kafka, and PostgreSQL are not attached to `artio-extension-control`.

`installation-secrets` creates a random controller bearer token and persists it with mode `0600`. A second named volume contains only this token and the encryption key required to derive plugin-scoped runtime credentials. The controller does not receive the database password, JWT signing secret, setup token, or database connection. Requests use `Authorization: Bearer <controller-token>` and are compared in constant time. The internal HTTP channel relies on the private Compose network; deployments that do not trust their container host/network must provide transport encryption and network policy.

The Docker socket is root-equivalent host administration. It is mounted only into `extension-controller`. That service uses a read-only root filesystem, a bounded `/tmp`, dropped Linux capabilities, `no-new-privileges`, no published ports, and only its two private networks. These controls reduce the controller's surrounding container privileges but do not make a compromised Docker daemon client harmless.

## Allowed operations

All routes are under `/internal/v1/extensions`, accept JSON, and require the bearer token:

| Method and route | Operation |
|---|---|
| `POST /install` | Pull the manifest's immutable image digest. |
| `POST /update` | Pull the new digest and replace only a stopped Artio-managed container. |
| `POST /enable` | Create/start the restricted plugin container with its credential generation and wait for readiness. |
| `POST /{pluginId}/disable` | Stop only the deterministic Artio container name for that plugin. |
| `POST /{pluginId}/uninstall` | Remove only a stopped container carrying matching Artio ownership labels. |
| `POST /rotate` | Replace a stopped container's credential generation and optionally restart it. |
| `POST /{pluginId}/health` | Return bounded health state and a coarse status code. |
| `POST /invoke` | Send the versioned, size/deadline-bounded plugin invocation and return its response. |

There is no arbitrary command, shell, mount, host-port, network, or raw Docker API route. The controller exposes neither GraphQL nor OpenAPI, and only the management health/info actuator endpoints are enabled. The Core validates registered manifests, lifecycle transitions, user authorization, configuration schemas, and invocation contracts before making a controller call. The controller independently enforces digest pinning, deterministic container names, Artio ownership labels, resource policy, and stopped-state rules before Docker operations.

## Error contract

The controller preserves the existing Core runtime contract: `400` for invalid operation input, `401` for an invalid controller token, `409` for an unsafe lifecycle state, `502` with `X-Artio-Extension-Status` for plugin HTTP failures, `503` for unavailable Docker/plugin runtime, and `504` for a deadline timeout. Error bodies contain only a stable error code; daemon exception messages and plugin request/response data are not included in controller error bodies.

The controller is an internal deployment contract, not a public customer API. Public plugin registration and lifecycle clients continue using the documented, versioned Core API.
