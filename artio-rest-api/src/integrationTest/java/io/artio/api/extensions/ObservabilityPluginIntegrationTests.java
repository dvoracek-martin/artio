package io.artio.api.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import io.artio.api.security.ExtensionCredentialService;
import io.artio.extensions.PluginManifest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Live acceptance of the separately packaged observability plugin bundle against real Docker images. */
class ObservabilityPluginIntegrationTests {
    @Test
    void installsRestartsProxiesAndUninstallsDigestPinnedBundle() throws Exception {
        Map<String, String> images = Map.of(
                "control", image("ARTIO_TEST_OBS_CONTROL_IMAGE"),
                "collector", image("ARTIO_TEST_OBS_COLLECTOR_IMAGE"),
                "prometheus", image("ARTIO_TEST_OBS_PROMETHEUS_IMAGE"),
                "jaeger", image("ARTIO_TEST_OBS_JAEGER_IMAGE"),
                "grafana", image("ARTIO_TEST_OBS_GRAFANA_IMAGE"));

        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var transport = new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig()).maxConnections(10).connectionTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(120)).build();
        try (DockerClient docker = DockerClientImpl.getInstance(config, transport)) {
            String testRunId = testRunId();
            String network = "artio-obs-it-" + testRunId + "-" + UUID.randomUUID().toString().substring(0, 8);
            String pluginId = "observability.artio";
            docker.createNetworkCmd().withName(network).withDriver("bridge")
                    .withLabels(Map.of("io.artio.test.run", testRunId)).exec();
            var runtime = new DockerExtensionRuntime(docker, network, 8080,
                    new ExtensionCredentialService("integration-test-key-material-that-is-at-least-32-bytes"), true,
                    "http://extension-egress:3128", testRunId);
            PluginManifest manifest = manifest(images);
            Map<String, String> settings = Map.of("grafanaAdminUser", "artio-admin",
                    "grafanaAdminPassword", "integration-test-grafana-password");
            try {
                runtime.install(manifest);
                runtime.enable(manifest, 1, settings);
                assertThat(runtime.health(manifest).healthy()).isTrue();
                assertGrafana(runtime, "200");
                assertBundleServices(docker, pluginId);

                Map<String, String> volumesBeforeRestart = ownedVolumeNames(docker, pluginId);
                assertThat(volumesBeforeRestart.keySet()).containsExactlyInAnyOrder("prometheus", "grafana");
                runtime.disable(pluginId);
                assertThat(runtime.health(pluginId, "/health").status()).isEqualTo("STOPPED");
                runtime.enable(manifest, 1, settings);
                assertThat(runtime.health(manifest).healthy()).isTrue();
                assertGrafana(runtime, "200");
                assertThat(ownedVolumeNames(docker, pluginId)).isEqualTo(volumesBeforeRestart);

                runtime.disable(pluginId);
                runtime.uninstall(pluginId);
                assertThat(runtime.health(pluginId, "/health").status()).isEqualTo("NOT_INSTALLED");
                assertThat(docker.listContainersCmd().withShowAll(true)
                        .withLabelFilter(Map.of("io.artio.extension", pluginId)).exec()).isEmpty();
                assertThat(ownedVolumeNames(docker, pluginId)).isEmpty();
            } finally {
                runtime.disable(pluginId);
                runtime.uninstall(pluginId);
                docker.removeNetworkCmd(network).exec();
            }
        }
    }

    private static String testRunId() {
        String runId = System.getenv("ARTIO_TEST_RUN_ID");
        return runId == null || runId.isBlank() ? "manual" : runId;
    }

    private static String image(String variable) {
        String value = System.getenv(variable);
        assumeTrue(value != null && value.matches(".+@sha256:[a-f0-9]{64}"),
                "Run scripts/verify-observability-plugin.ps1 to provide digest-pinned observability images");
        return value;
    }

    private static void assertGrafana(DockerExtensionRuntime runtime, String expectedStatus) throws Exception {
        String userId = "fbf38b6f-431a-4f5d-aae7-85ad63f9a0e1";
        var response = runtime.proxyObservability("observability.artio", "GET", "/api/v1/user", null,
                userId, "admin@example.invalid");
        assertThat(response.statusCode()).isEqualTo(Integer.parseInt(expectedStatus));
        var user = new ObjectMapper().readTree(response.body());
        assertThat(user.path("login").asText()).isEqualTo("artio-" + userId);
        assertThat(user.path("email").asText()).isEqualTo("admin@example.invalid");
        assertThat(user.path("isGrafanaAdmin").asBoolean()).isFalse();
        var organizations = runtime.proxyObservability("observability.artio", "GET", "/api/v1/user/orgs", null,
                userId, "admin@example.invalid");
        assertThat(new ObjectMapper().readTree(organizations.body()).get(0).path("role").asText()).isEqualTo("Viewer");

        String secondUserId = "52b079de-ef9c-4b4b-af78-193e082c5124";
        var secondUser = runtime.proxyObservability("observability.artio", "GET", "/api/v1/user", null,
                secondUserId, "operator@example.invalid");
        assertThat(new ObjectMapper().readTree(secondUser.body()).path("login").asText())
                .isEqualTo("artio-" + secondUserId);
        var secondOrganizations = runtime.proxyObservability("observability.artio", "GET", "/api/v1/user/orgs", null,
                secondUserId, "operator@example.invalid");
        assertThat(new ObjectMapper().readTree(secondOrganizations.body()).get(0).path("role").asText()).isEqualTo("Viewer");

        var anonymous = runtime.proxyObservability("observability.artio", "GET", "/api/v1/user", null);
        assertThat(anonymous.statusCode()).isEqualTo(401);
    }

    private static void assertBundleServices(DockerClient docker, String pluginId) {
        for (String service : List.of("collector", "prometheus", "jaeger", "grafana")) {
            var container = docker.inspectContainerCmd(DockerExtensionRuntime.serviceName(pluginId, service)).exec();
            assertThat(container.getState().getRunning()).as(service + " running").isTrue();
            assertThat(container.getConfig().getUser()).as(service + " non-root").isEqualTo("10001:10001");
            assertThat(container.getHostConfig().getReadonlyRootfs()).as(service + " read-only root").isTrue();
            assertThat(container.getHostConfig().getCapDrop()).as(service + " dropped capabilities").contains(Capability.ALL);
        }
    }

    private static Map<String, String> ownedVolumeNames(DockerClient docker, String pluginId) {
        return docker.listVolumesCmd().exec().getVolumes().stream()
                .filter(volume -> volume.getLabels() != null && pluginId.equals(volume.getLabels().get("io.artio.extension")))
                .collect(java.util.stream.Collectors.toMap(volume -> volume.getLabels().get("io.artio.extension.service"),
                        com.github.dockerjava.api.command.InspectVolumeResponse::getName));
    }

    private static PluginManifest manifest(Map<String, String> images) {
        var configuration = Map.of(
                "grafanaAdminUser", new PluginManifest.ConfigField("text", true, false, "Grafana administrator",
                        "Local Grafana account name.", null, List.of(), 1, 64, null, null),
                "grafanaAdminPassword", new PluginManifest.ConfigField("secret", true, true, "Grafana administrator password",
                        "Password for the plugin's isolated Grafana instance.", null, List.of(), 16, 256, null, null));
        var services = List.of(
                new PluginManifest.BundleService("collector", images.get("collector"), 13133, "/", Map.of(),
                        List.of("jaeger"), List.of(), List.of(4317, 4318, 9464)),
                new PluginManifest.BundleService("prometheus", images.get("prometheus"), 9090, "/-/healthy", Map.of(),
                        List.of("collector"), List.of("/prometheus")),
                new PluginManifest.BundleService("jaeger", images.get("jaeger"), 16686, "/", Map.of(),
                        List.of("main"), List.of(), List.of(4317, 4318)),
                new PluginManifest.BundleService("grafana", images.get("grafana"), 3000, "/api/v1/health",
                        Map.of("GF_SECURITY_ADMIN_USER", "grafanaAdminUser", "GF_SECURITY_ADMIN_PASSWORD", "grafanaAdminPassword"),
                        List.of("prometheus", "jaeger"), List.of("/var/lib/grafana")));
        return new PluginManifest("1", "observability.artio", "Artio Observability", "Artio", "1.0.0",
                "Optional OTLP collector, dashboards and traces.", "0.1.0", "0.9.0", images.get("control"),
                List.of(), List.of(), configuration, List.of(), "/health", "/diagnostics",
                PluginManifest.GeographicAvailability.global(), services);
    }
}
