# Supported deployment topology

The currently verified self-hosted deployment is a **single Docker host with one replica each** of `rest-api`, `event-worker`, and `notification-worker`. The standard Compose stack starts one of each. Do not scale these services or run multiple Artio hosts as a supported deployment until the multi-instance release acceptance in `docs/ROADMAP.md` passes.

This limit reflects current process and storage boundaries:

- `RateLimitFilter` uses Redis counters when configured; its default optional mode falls back to per-process in-memory windows during a Redis outage. Multi-instance deployments must turn on strict shared rate limiting, which returns HTTP 503 during a Redis outage rather than applying divergent local quotas.
- `CacheConfiguration` uses per-process caches unless `ARTIO_CACHE_REDIS_ENABLED=true` and Redis is configured.
- The extension circuit breaker is per API process.
- Compose stores product media in the local `artio-catalog-media` Docker volume. It is persistent on that host but is not a shared multi-host filesystem. Product media already documents the shared-storage requirement for multi-host use.
- The Compose API publishes its port directly; the default stack does not include an ingress/load-balancer topology for API replicas.

Keep the database, Kafka, installation secrets, product media, API, controller and workers on the same host under the standard Compose installation. Back up PostgreSQL, the installation-secret volume and media together as described in [backup and recovery](backup-recovery.md).

Horizontal scaling becomes supported only after a release gate exercises at least two API, event-worker and notification-worker instances under concurrent load, including shared rate-limit/cache behavior, media reads/writes and recovery from Redis, PostgreSQL, Kafka and plugin-controller failures. A deployment that needs multiple hosts must also configure genuinely shared media storage and an ingress/load balancer; a named local Docker volume does not satisfy that requirement.

For a multi-API deployment, enable the Compose `cache` profile, set `ARTIO_CACHE_REDIS_ENABLED=true` and `ARTIO_RATE_LIMIT_REDIS_REQUIRED=true` on every API replica, and place all replicas behind an ingress/load balancer. The strict rate-limit setting rejects startup if Redis-backed accounting is disabled and returns HTTP 503 while Redis is unavailable, rather than silently splitting request quotas across process-local counters. Redis-backed application caches are shared under the same setting. This configuration is a prerequisite for multi-instance acceptance, not evidence that the deployment topology is yet supported.

`SharedRateLimitRedisIntegrationTests.independentApiLimitersShareRedisQuotaAndFailClosedWhenRedisStops` exercises two independent Redis connection factories against the pinned Redis image. It verifies a quota shared across limiter instances, correct HTTP 429 enforcement, and HTTP 503 after Redis stops. This proves the limiter's shared-store behavior; it does not replace full multi-replica deployment acceptance.

Order WebSocket updates use Redis pub/sub topic `artio:realtime:order-updates:v1` when shared Redis is enabled. Core publishes only after the order transaction commits; each API listener forwards the update through its local authenticated user destination. A real Redis integration test verifies two independent listeners receive the update. Redis pub/sub is intentionally ephemeral: if Redis is unavailable, the local replica may still notify its own sessions, while sessions connected elsewhere may miss that realtime event and must refresh from the authoritative order API. Commerce writes do not depend on realtime delivery.
