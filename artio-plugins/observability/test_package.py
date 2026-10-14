import json
import pathlib
import unittest


ROOT = pathlib.Path(__file__).parent


class ObservabilityPackageTests(unittest.TestCase):
    def test_manifest_declares_health_gated_bundle_and_plugin_owned_state(self):
        manifest = json.loads((ROOT / "manifest.template.json").read_text(encoding="utf-8"))
        services = {service["id"]: service for service in manifest["services"]}
        self.assertEqual({"collector", "prometheus", "jaeger", "grafana"}, set(services))
        self.assertEqual(["collector"], services["prometheus"]["dependsOn"])
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

    def test_sidecar_urls_match_the_runtime_container_names(self):
        collector = (ROOT / "collector" / "otel-collector.yaml").read_text(encoding="utf-8")
        prometheus = (ROOT / "prometheus" / "prometheus.yml").read_text(encoding="utf-8")
        datasource = (ROOT / "grafana" / "provisioning" / "datasources" / "prometheus.yaml").read_text(encoding="utf-8")
        self.assertIn("artio-svc-b2bbfd7d2eb9-jaeger:4318", collector)
        self.assertIn("artio-svc-b2bbfd7d2eb9-collector:9464", prometheus)
        self.assertIn("artio-svc-b2bbfd7d2eb9-prometheus:9090", datasource)
        self.assertIn("artio-svc-b2bbfd7d2eb9-jaeger:16686", datasource)


if __name__ == "__main__":
    unittest.main()
