# Artio Observability plugin bundle

This is the source package for an independently managed `observability.artio` Docker plugin bundle. It contains a Core health/diagnostics image and separate OpenTelemetry Collector, Prometheus, Jaeger and Grafana images. Upstream image bases are version and multi-platform digest pinned. The bundle uses OTLP and declares service startup dependencies; all UIs and data services remain on the private extension network. Core's optional exporters send OTLP through a bearer-authenticated endpoint on the isolated lifecycle controller. That gateway forwards only metrics/traces protobuf requests to the managed collector's declared private port, so Core never joins the plugin network.

The Grafana administrator credentials are schema-backed plugin settings. The password is a secret field bound only to the Grafana container and is encrypted by Core at rest. Prometheus and Grafana state use plugin-owned named volumes (`/prometheus` and `/var/lib/grafana`); updates preserve these volumes and uninstall removes them after stopped managed containers are removed. Jaeger currently uses in-memory trace storage.

Build the five local images with `./build-images.ps1`. The manifest is a template and deliberately contains non-digest placeholders. It is not installable until the images are published by the project owner, their registry digests are verified, and the template is filled with those exact digests. No image digest is fabricated here. Publishing immutable image digests remains a release prerequisite.

Core remains optional: OTLP export is disabled unless configured. The commerce runtime does not depend on this bundle's health. The Backoffice Plugin Manager can install, configure, enable, disable, update, roll back and uninstall a valid bundle. Grafana is exposed through an authenticated, read-only same-origin proxy after the operator enables the plugin. No Grafana administrator credential or Artio bearer token is exposed to the browser.

The Compose overlay under `artio-docker/` remains a local development/reference path. It is not the production plugin lifecycle.
