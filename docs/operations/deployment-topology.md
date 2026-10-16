# Supported deployment topology

The currently verified self-hosted deployment is a **single Docker host with one replica each** of `rest-api`, `event-worker`, and `notification-worker`. The standard Compose stack starts one of each. Do not scale these services or run multiple Artio hosts as a supported deployment until the multi-instance release acceptance in `docs/ROADMAP.md` passes.

This limit reflects current process and storage boundaries:

- `RateLimitFilter` uses Redis counters when Redis is reachable, but falls back to per-process in-memory windows when Redis is absent or unavailable. Multiple API instances can therefore enforce different effective quotas during a Redis outage.
- `CacheConfiguration` uses per-process caches unless `ARTIO_CACHE_REDIS_ENABLED=true` and Redis is configured.
- The extension circuit breaker is per API process.
- Compose stores product media in the local `artio-catalog-media` Docker volume. It is persistent on that host but is not a shared multi-host filesystem. Product media already documents the shared-storage requirement for multi-host use.
- The Compose API publishes its port directly; the default stack does not include an ingress/load-balancer topology for API replicas.

Keep the database, Kafka, installation secrets, product media, API, controller and workers on the same host under the standard Compose installation. Back up PostgreSQL, the installation-secret volume and media together as described in [backup and recovery](backup-recovery.md).

Horizontal scaling becomes supported only after a release gate exercises at least two API, event-worker and notification-worker instances under concurrent load, including shared rate-limit/cache behavior, media reads/writes and recovery from Redis, PostgreSQL, Kafka and plugin-controller failures. A deployment that needs multiple hosts must also configure genuinely shared media storage and an ingress/load balancer; a named local Docker volume does not satisfy that requirement.
