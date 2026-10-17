# Artio Observability plugin bundle

This is the source package for an independently managed `observability.artio` Docker plugin bundle. It contains a Core health/diagnostics image and separate OpenTelemetry Collector, Prometheus, Jaeger and Grafana images. Upstream image bases are version and multi-platform digest pinned. The bundle uses OTLP and declares service startup dependencies; all UIs and data services remain on the private extension network. Core's optional exporters send OTLP through a bearer-authenticated endpoint on the isolated lifecycle controller. That gateway forwards only metrics/traces protobuf requests to the managed collector's declared private port, so Core never joins the plugin network.

The Grafana administrator credentials are schema-backed plugin settings. The password is a secret field bound only to the Grafana container and is encrypted by Core at rest. Anonymous access is disabled. The authenticated Artio read-only proxy maps each ADMIN or SUPERADMIN to a separate UUID-based Grafana user, syncs that account's email, and assigns the Grafana Viewer organization role. Browser-supplied identity and Artio bearer tokens are never forwarded to Grafana. Prometheus and Grafana state use plugin-owned named volumes (`/prometheus` and `/var/lib/grafana`); updates preserve these volumes and uninstall removes them after stopped managed containers are removed. Jaeger currently uses in-memory trace storage.

Build the five local images with `./build-images.ps1`. It tags them under `artio-observability/` and does not publish them. The manifest is a template and deliberately contains non-digest placeholders under the neutral example registry `registry.example`. Local Docker acceptance builds and installs all five images using exact digests from a disposable local registry; those fixture digests are not public release digests. The manifest remains un-installable until release publishes the images and the template is filled with the exact registry-reported digests. No image digest or registry owner is fabricated here.

After publishing and promoting all five images, prepare a release manifest with the exact references returned by the public registry. The command creates a new `manifest.json` in the output directory and refuses tags, placeholders, private staging paths, mixed registry hosts, unexpected repositories, or overwriting existing package files:

```powershell
python .\prepare_release.py `
  --version 1.0.0 `
  --output .\release-package `
  --control-image 'registry.example/public/plugins/observability-control@sha256:<64-hex-digest>' `
  --collector-image 'registry.example/public/plugins/observability-collector@sha256:<64-hex-digest>' `
  --prometheus-image 'registry.example/public/plugins/observability-prometheus@sha256:<64-hex-digest>' `
  --jaeger-image 'registry.example/public/plugins/observability-jaeger@sha256:<64-hex-digest>' `
  --grafana-image 'registry.example/public/plugins/observability-grafana@sha256:<64-hex-digest>'
```

Replace the example host and digests with values reported by the registry after promotion. This only prepares the manifest; complete the catalog metadata/assets and use Marketplace's separate publisher and admin approval steps before treating the plugin as installable.

Core remains optional: OTLP export is disabled unless configured. The commerce runtime does not depend on this bundle's health. The Backoffice Plugin Manager can install, configure, enable, disable, update, roll back and uninstall a valid bundle. Grafana is exposed through an authenticated, read-only same-origin proxy after the operator enables the plugin. No Grafana administrator credential or Artio bearer token is exposed to the browser.

The Compose overlay under `artio-docker/` remains a local development/reference path. It is not the production plugin lifecycle.
