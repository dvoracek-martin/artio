package io.artio.api.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import io.artio.api.security.ExtensionCredentialService;
import io.artio.extensions.PluginManifest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Real Docker test, enabled by scripts/verify-extension-runtime.ps1 after building and pushing the local fixture images. */
class DockerExtensionRuntimeIntegrationTests {
    @Test
    void installsInvokesUpdatesAndRollsBackRealRestrictedContainers() throws Exception {
        String firstImage = System.getenv("ARTIO_TEST_EXTENSION_IMAGE_V1");
        String secondImage = System.getenv("ARTIO_TEST_EXTENSION_IMAGE_V2");
        assumeTrue(firstImage != null && secondImage != null, "Run scripts/verify-extension-runtime.ps1 to provide pinned fixture images");

        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var transport = new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig()).maxConnections(10).connectionTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(120)).build();
        try (DockerClient docker = DockerClientImpl.getInstance(config, transport)) {
            String network = "artio-ext-it-" + UUID.randomUUID().toString().substring(0, 8);
            String pluginId = "runtime.e2e" + UUID.randomUUID().toString().replace("-", "");
            // The test host is outside Docker's private network, so this harness uses loopback port forwarding.
            // Production uses the private, unpublished extension network and is separately asserted by runtime policy tests.
            docker.createNetworkCmd().withName(network).withDriver("bridge").exec();
            var runtime = new DockerExtensionRuntime(docker, network, 8080,
                    new ExtensionCredentialService("integration-test-key-material-that-is-at-least-32-bytes"), true);
            PluginManifest current = manifest(pluginId, firstImage, "1.0.0");
            PluginManifest next = manifest(pluginId, secondImage, "2.0.0");
            try {
                runtime.install(current);
                runtime.enable(current, 1);
                assertInvocation(runtime, pluginId, "1");

                runtime.disable(pluginId);
                runtime.update(next);
                runtime.enable(next, 1);
                assertInvocation(runtime, pluginId, "2");

                runtime.disable(pluginId);
                runtime.update(current);
                runtime.enable(current, 1);
                assertInvocation(runtime, pluginId, "1");

                runtime.disable(pluginId);
                runtime.uninstall(pluginId);
                assertThat(runtime.health(pluginId, "/health").status()).isEqualTo("NOT_INSTALLED");
            } finally {
                runtime.disable(pluginId);
                try { docker.removeContainerCmd(DockerExtensionRuntime.containerName(pluginId)).withForce(true).exec(); }
                catch (com.github.dockerjava.api.exception.NotFoundException ignored) { }
                docker.removeNetworkCmd(network).exec();
            }
        }
    }

    private static void assertInvocation(DockerExtensionRuntime runtime, String pluginId, String expectedVersion) throws Exception {
        var result = runtime.invoke(pluginId, 1, "payment.provider.v1", "authorize", UUID.randomUUID().toString(),
                "{}", "{\"amount\":100}", Duration.ofSeconds(5));
        assertThat(new ObjectMapper().readTree(result.responseJson()).path("runtimeVersion").asText()).isEqualTo(expectedVersion);
    }

    private static PluginManifest manifest(String pluginId, String image, String version) {
        return new PluginManifest("1", pluginId, "Reference extension", "Artio", version,
                "Local lifecycle test fixture", "0.1.0", "9.9.9", image, List.of(), List.of("payment.process"),
                Map.of(), List.of("payment.provider.v1"), "/health", "/diagnostics");
    }
}
