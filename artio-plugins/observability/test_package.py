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
