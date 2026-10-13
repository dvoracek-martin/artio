# Kubernetes deployment

Apply `configmap.yaml`, create the `artio-secrets` Secret with database password, JWT secret, Stripe API key and webhook secret, then apply the API, storefront, backoffice and ingress manifests. PostgreSQL, Redis, Kafka, OpenSearch, DNS, TLS and the ingress controller are expected to be supplied by the cluster or managed cloud services.

Use immutable image tags for production and configure a restricted service account, network policies, secret manager integration and a production-specific database URL before rollout. The example uses `latest`-style image names only as placeholders.
