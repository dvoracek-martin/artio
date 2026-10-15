# Dependency and container security scans

The pull-request and push workflow checks both frontend lockfiles with the official npm registry and fails on moderate or higher npm advisories. It builds and scans the API, Storefront, Backoffice, all provider/search plugins, the OpenSearch engine, and each bundled observability image (control, collector, Grafana, Jaeger, Prometheus). Trivy checks for HIGH and CRITICAL OS and library vulnerabilities. It ignores advisories without an available fix and fails for any remaining finding at those severities. The Trivy action is pinned to a full commit SHA and the scanner version is explicit.

The scan is a release gate, not proof that an image has no risk. Review scanner output and upstream advisories when dependencies, base images, or scan policy change. A new finding blocks the workflow until the package is updated or an owner documents a narrow, time-bounded exception with its rationale and compensating control. Do not suppress a test or broadly ignore a vulnerability to obtain a green build.

Docker base images are pinned by digest so an input image cannot silently move between builds. API and Nginx runtime builds apply the current distribution security package updates before copying application artifacts; the API image also removes Temurin's unused Pebble test certificate server. Rebuild and scan images regularly to receive operating-system fixes while retaining the reviewed digest as the recorded input. Frontend npm dependency audits run against `registry.npmjs.org`; this explicit registry is required because the repository's configured mirror does not expose the npm audit endpoint.

Local equivalents from the repository root:

```powershell
npm --registry=https://registry.npmjs.org audit --audit-level=moderate --prefix artio-storefront
npm --registry=https://registry.npmjs.org audit --audit-level=moderate --prefix artio-backoffice
docker build -f artio-docker/Dockerfile -t artio/api:local .
docker build -f artio-docker/Dockerfile.frontend --build-arg MODULE=artio-storefront --build-arg APP_NAME=storefront -t artio/storefront:local .
docker build -f artio-docker/Dockerfile.frontend --build-arg MODULE=artio-backoffice --build-arg APP_NAME=backoffice -t artio/backoffice:local .
```

CI also builds and scans every bundled plugin image that has a Dockerfile. For a local scan, use the same Trivy version and flags from `.github/workflows/build.yml` against each resulting image. Keep scanner databases and build output out of source control.

For a digest-pinned upstream image, a remote Trivy scan avoids creating a local Docker image:

```powershell
$scanCache = 'D:\Temp\artio-trivy-cache'
trivy image --image-src remote --cache-dir $scanCache --scanners vuln --ignore-unfixed --pkg-types os,library --severity HIGH,CRITICAL --format json --output opensearch-scan.json 'opensearchproject/opensearch@sha256:<reviewed-digest>'
trivy clean --all --cache-dir $scanCache
```

The Java vulnerability database may use close to 1 GB of disk. Keep the JSON report outside source control, clean the cache after the scan when it is not needed for another scan, and retain the exact image digest with the result. A remote scan does not test runtime behavior; run the corresponding integration test separately when changing the engine version.

## Java platform update

Spring Boot's Gradle plugin and dependency BOM must be upgraded together in `buildSrc/build.gradle.kts` and `buildSrc/src/main/kotlin/artio.spring-adapter.gradle.kts`. After an upgrade, run the full Gradle test and integration-test gate, rebuild the API image, and inspect its Trivy report. The project uses Java 21 and Gradle 8.14.6, with the wrapper distribution SHA-256 pinned in `gradle/wrapper/gradle-wrapper.properties`. Evaluate major framework changes against their documented compatibility and migration guide before adopting them.
