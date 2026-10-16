#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${GITHUB_SHA:-}" ]]; then
  echo "GITHUB_SHA is required to bind the image inventory to this source revision." >&2
  exit 2
fi

output_dir="build/sbom"
mkdir -p "$output_dir"
inventory="$output_dir/image-inventory.tsv"
printf 'source_commit\timage\timage_id\tsbom\tsbom_sha256\n' > "$inventory"

images=(
  api
  storefront
  backoffice
  plugin-stripe
  plugin-paypal
  plugin-postfinance
  plugin-swiss-post
  plugin-brevo
  plugin-opensearch
  plugin-opensearch-engine
  plugin-observability-control
  plugin-observability-collector
  plugin-observability-grafana
  plugin-observability-jaeger
  plugin-observability-prometheus
)

for name in "${images[@]}"; do
  image="artio/${name}:${GITHUB_SHA}"
  image_id="$(docker image inspect --format '{{.Id}}' "$image")"
  sbom="$output_dir/${name}.cdx.json"

  trivy image --format cyclonedx --output "$sbom" "$image"
  jq -e '.bomFormat == "CycloneDX" and (.components | type == "array")' "$sbom" >/dev/null
  sbom_sha256="$(sha256sum "$sbom" | cut -d ' ' -f 1)"
  printf '%s\t%s\t%s\t%s\t%s\n' "$GITHUB_SHA" "$image" "$image_id" "${sbom##*/}" "$sbom_sha256" >> "$inventory"
done

echo "Generated and validated ${#images[@]} CycloneDX image SBOMs in $output_dir."
