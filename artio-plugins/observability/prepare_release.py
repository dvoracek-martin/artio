"""Create a release manifest from registry-reported immutable image digests."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Mapping


ROOT = Path(__file__).resolve().parent
IMAGE_IDS = ("control", "collector", "prometheus", "jaeger", "grafana")
REPOSITORIES = {image_id: f"observability-{image_id}" for image_id in IMAGE_IDS}
IMAGE_REFERENCE = re.compile(
    r"^(?P<registry>[a-z0-9.-]+(?::[0-9]{1,5})?)/(?P<repository>[a-z0-9._/-]+)"
    r"@sha256:(?P<digest>[a-f0-9]{64})$"
)
SEMVER = re.compile(
    r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$"
)


def resolve_manifest(template: Mapping[str, object], version: str, images: Mapping[str, str]) -> dict:
    if not SEMVER.fullmatch(version):
        raise ValueError("Release version must use semantic versioning.")
    if set(images) != set(IMAGE_IDS):
        raise ValueError("Exactly the control, collector, prometheus, jaeger, and grafana image references are required.")

    resolved: dict[str, str] = {}
    registries: set[str] = set()
    for image_id, reference in images.items():
        match = IMAGE_REFERENCE.fullmatch(reference)
        if match is None:
            raise ValueError(f"{image_id} image must be a lowercase sha256 digest reference.")
        if match.group("repository").rsplit("/", 1)[-1] != REPOSITORIES[image_id]:
            raise ValueError(f"{image_id} image must use the {REPOSITORIES[image_id]} repository.")
        if "staging" in match.group("repository").split("/"):
            raise ValueError(f"{image_id} image cannot reference a private staging repository.")
        resolved[image_id] = reference
        registries.add(match.group("registry"))
    if len(registries) != 1:
        raise ValueError("All bundle images must come from the same public registry host.")

    manifest = json.loads(json.dumps(template))
    manifest["version"] = version
    manifest["image"] = resolved["control"]
    services = {service["id"]: service for service in manifest["services"]}
    if set(services) != set(IMAGE_IDS) - {"control"}:
        raise ValueError("Manifest service identities do not match the observability bundle.")
    for service_id, service in services.items():
        service["image"] = resolved[service_id]

    references = [manifest["image"], *(service["image"] for service in manifest["services"])]
    if any("REPLACE_WITH_PUBLISHED_IMAGE_DIGEST" in reference for reference in references):
        raise ValueError("Resolved manifest still contains an unpublished image placeholder.")
    return manifest


def prepare_release_manifest(version: str, images: Mapping[str, str], output: Path) -> Path:
    template = json.loads((ROOT / "manifest.template.json").read_text(encoding="utf-8"))
    manifest = resolve_manifest(template, version, images)
    if output.exists() and (not output.is_dir() or next(output.iterdir(), None) is not None):
        raise ValueError("Output directory must be new or empty.")
    destination = output / "manifest.json"
    output.mkdir(parents=True, exist_ok=True)
    with destination.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(manifest, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    return destination


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True, help="Immutable plugin release version in SemVer format")
    parser.add_argument("--output", required=True, type=Path, help="New or empty directory for manifest.json")
    for image_id in IMAGE_IDS:
        parser.add_argument(f"--{image_id}-image", required=True, help="Registry-reported image reference ending in @sha256:<64 hex>")
    args = parser.parse_args()
    images = {image_id: getattr(args, f"{image_id}_image") for image_id in IMAGE_IDS}
    manifest_path = prepare_release_manifest(args.version, images, args.output)
    print(f"Prepared {manifest_path}; no image was built, pushed, or published.")


if __name__ == "__main__":
    main()
