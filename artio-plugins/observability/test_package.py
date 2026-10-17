import json
import pathlib
import sys
import tempfile
import unittest


ROOT = pathlib.Path(__file__).parent
sys.path.insert(0, str(ROOT))
from prepare_release import IMAGE_IDS, prepare_release_manifest, resolve_manifest


class ObservabilityPackageTests(unittest.TestCase):
    def test_manifest_declares_health_gated_bundle_and_plugin_owned_state(self):
        manifest = json.loads((ROOT / "manifest.template.json").read_text(encoding="utf-8"))
        services = {service["id"]: service for service in manifest["services"]}
        self.assertEqual({"collector", "prometheus", "jaeger", "grafana"}, set(services))
        self.assertEqual(["collector"], services["prometheus"]["dependsOn"])
        self.assertEqual(["jaeger"], services["collector"]["dependsOn"])
        self.assertEqual({"prometheus", "jaeger"}, set(services["grafana"]["dependsOn"]))
        self.assertEqual(["/prometheus"], services["prometheus"]["persistentPaths"])
        self.assertEqual(["/var/lib/grafana"], services["grafana"]["persistentPaths"])
        self.assertEqual([4317, 4318, 9464], services["collector"]["internalPorts"])
        self.assertIn("job_name: artio-api", (ROOT / "prometheus" / "prometheus.yml").read_text(encoding="utf-8"))
        self.assertEqual("secret", manifest["configuration"]["grafanaAdminPassword"]["type"])
        self.assertEqual("grafanaAdminPassword", services["grafana"]["configurationBindings"]["GF_SECURITY_ADMIN_PASSWORD"])

    def test_images_are_intentionally_unpublished_templates(self):
        manifest = json.loads((ROOT / "manifest.template.json").read_text(encoding="utf-8"))
        images = [manifest["image"], *(service["image"] for service in manifest["services"])]
        self.assertTrue(all("REPLACE_WITH_PUBLISHED_IMAGE_DIGEST" in image for image in images))

    def test_release_manifest_uses_only_supplied_registry_digests(self):
        template = json.loads((ROOT / "manifest.template.json").read_text(encoding="utf-8"))
        images = {
            image_id: f"registry.example/public/plugins/observability-{image_id}@sha256:{str(index) * 64}"
            for index, image_id in enumerate(IMAGE_IDS, start=1)
        }

        release = resolve_manifest(template, "1.0.1", images)

        self.assertEqual("1.0.1", release["version"])
        self.assertEqual(images["control"], release["image"])
        self.assertEqual(images["collector"], next(s for s in release["services"] if s["id"] == "collector")["image"])
        self.assertFalse(any("REPLACE_WITH_PUBLISHED_IMAGE_DIGEST" in ref
                             for ref in [release["image"], *(service["image"] for service in release["services"])]))

    def test_release_manifest_rejects_placeholders_mixed_hosts_and_invalid_versions(self):
        template = json.loads((ROOT / "manifest.template.json").read_text(encoding="utf-8"))
        images = {
            image_id: f"registry.example/public/plugins/observability-{image_id}@sha256:{str(index) * 64}"
            for index, image_id in enumerate(IMAGE_IDS, start=1)
        }

        invalid_cases = [
            ({**images, "control": template["image"]}, "1.0.1"),
            ({**images, "grafana": images["grafana"].replace("registry.example", "other.example")}, "1.0.1"),
            ({**images, "control": images["control"].replace("public/plugins", "staging/plugins")}, "1.0.1"),
            (images, "01.0.1"),
        ]
        for invalid_images, version in invalid_cases:
            with self.subTest(images=invalid_images, version=version):
                with self.assertRaises(ValueError):
                    resolve_manifest(template, version, invalid_images)

    def test_release_manifest_preparation_never_overwrites_package_files(self):
        images = {
            image_id: f"registry.example/public/plugins/observability-{image_id}@sha256:{str(index) * 64}"
            for index, image_id in enumerate(IMAGE_IDS, start=1)
        }
        with tempfile.TemporaryDirectory() as temporary:
            output = pathlib.Path(temporary) / "release"
            destination = prepare_release_manifest("1.0.1", images, output)
            generated = json.loads(destination.read_text(encoding="utf-8"))
            self.assertEqual("1.0.1", generated["version"])
            with self.assertRaises(ValueError):
                prepare_release_manifest("1.0.2", images, output)

    def test_sidecar_urls_match_the_runtime_container_names(self):
        collector = (ROOT / "collector" / "otel-collector.yaml").read_text(encoding="utf-8")
        prometheus = (ROOT / "prometheus" / "prometheus.yml").read_text(encoding="utf-8")
        datasource = (ROOT / "grafana" / "provisioning" / "datasources" / "prometheus.yaml").read_text(encoding="utf-8")
        self.assertIn("artio-svc-b2bbfd7d2eb9-jaeger:4318", collector)
        self.assertIn("artio-svc-b2bbfd7d2eb9-collector:9464", prometheus)
        self.assertIn("artio-svc-b2bbfd7d2eb9-prometheus:9090", datasource)
        self.assertIn("artio-svc-b2bbfd7d2eb9-jaeger:16686", datasource)

    def test_grafana_requires_per_user_auth_proxy_and_assigns_read_only_org_role(self):
        dockerfile = (ROOT / "grafana" / "Dockerfile").read_text(encoding="utf-8")
        self.assertIn("GF_AUTH_ANONYMOUS_ENABLED=false", dockerfile)
        self.assertIn("GF_AUTH_PROXY_ENABLED=true", dockerfile)
        self.assertIn("GF_AUTH_PROXY_AUTO_SIGN_UP=true", dockerfile)
        self.assertIn("GF_USERS_AUTO_ASSIGN_ORG_ROLE=Viewer", dockerfile)
        self.assertIn("GF_PATHS_PLUGINS=/var/lib/grafana/plugins", dockerfile)


if __name__ == "__main__":
    unittest.main()
