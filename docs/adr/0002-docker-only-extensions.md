# ADR-0002: Docker containers are the only plugin runtime

- **Status:** Accepted — mandated by `ARTIO-Codex-Final-Implementation-Directive-v3.1.md` v3.1.
- **Date:** 2026-10-13.
- **Owner:** Product/architecture decision supplied in the directive.

## Context

Current provider integrations are in the Core REST application. Stripe checkout/webhook/refund behavior lives in `artio-rest-api` (`PaymentController`), while other provider implementations are absent. No plugin manifest, Extension API, container lifecycle manager or plugin configuration workflow exists. The product brief requires independently versioned Docker plugin containers and forbids arbitrary plugin JARs inside the Core JVM.

## Decision

Core owns stable provider-independent payment, shipping and other extension contracts. External/provider-specific behavior executes only in a separately deployed Docker container and is managed by a Core lifecycle/configuration service exposed in Backoffice. The host runtime never loads arbitrary plugin classes/JARs into the Core process.

## Contract and security consequences

- Every plugin has a versioned, machine-readable manifest with compatibility/API capabilities, image reference/digest, dependencies, permissions, schema-backed config, extension points and health/diagnostic contract.
- Core validates manifest/schema/capabilities and image trust before activation. UI metadata cannot grant authorization.
- Core-to-plugin calls are authenticated, versioned, schema-validated, size-limited, deadline-bounded and observable; retries are allowed only for operations with an idempotency contract.
- Containers run with least privilege and explicit resource/network/secrets policy. Docker is not claimed as a sufficient sandbox by itself.
- Secret configuration is write-only from the UI, protected at rest using an operator-controlled key/secret manager, redacted from all diagnostics, and has an explicit rotation plan.
- Install/enable/update/disable/rollback/uninstall operations are state-validated and audited. Uninstall does not silently erase business records created by a plugin.
- Plugin outage/timeout is isolated and returned as a stable Core error. No plugin failure may crash Core or commit unrelated partial state.
- Core and plugin images have independent versions; lifecycle changes do not require rebuilding Core.

## Alternatives rejected

- In-process JAR/classloader plugins: violates selected runtime and provides weak failure isolation.
- Provider code hard-coded into Core: prevents independent provider releases and violates the integration boundary.
- Arbitrary image launch with host privileges: rejected; trusted image identity, constrained runtime and explicit operator policy are required.

## Migration

Introduce contract and lifecycle management before moving Stripe behavior. Keep the current checkout/order/payment records intact; adapt webhook and refund behavior behind the plugin contract and test idempotency/replay. Do not claim a provider plugin until its real container, sandbox flow, setup, failure diagnostics and contract tests pass.

## Decision register fields

- **Question:** Which plugin distribution/runtime is supported? **Decision:** Docker containers only.
- **Alternatives:** in-process JAR, remote service without managed lifecycle, Docker containers. **Selected:** Docker containers.
- **Trade-off:** process isolation and independent release at the cost of deployment/runtime operation, network security and container-host residual risk.
- **Blocks:** no product-owner blocker. Trusted registry policy and initial provider selection are recorded as non-blocking operational decisions before the plugin proof/provider implementation.
