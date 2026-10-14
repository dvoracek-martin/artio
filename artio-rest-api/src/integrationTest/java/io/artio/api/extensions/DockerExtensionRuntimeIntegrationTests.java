package io.artio.api.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        verifyLifecycle(false);
    }

    @Test
    void managesSidecarHealthAndPreservesDataAcrossBundleUpdateAndRollback() throws Exception {
        verifyLifecycle(true);
    }

    private void verifyLifecycle(boolean bundle) throws Exception {
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
            PluginManifest current = manifest(pluginId, firstImage, "1.0.0", bundle);
            PluginManifest next = manifest(pluginId, secondImage, "2.0.0", bundle);
            try {
                runtime.install(current);
                runtime.enable(current, 1);
                assertInvocation(runtime, pluginId, "1");
                assertThatThrownBy(() -> runtime.invoke(pluginId, 1, "payment.provider.v1", "slow", UUID.randomUUID().toString(),
                        "{}", "{}", Duration.ofMillis(100)))
                        .isInstanceOf(io.artio.extensions.ExtensionRuntimePort.ExtensionTimeoutException.class);
                assertThat(runtime.health(current).healthy()).isTrue();
                if (bundle) {
                    assertThat(runtime.health(current).healthy()).isTrue();
                    var companion = docker.inspectContainerCmd(DockerExtensionRuntime.serviceName(pluginId, "companion")).exec();
                    assertThat(companion.getConfig().getUser()).isEqualTo("10001:10001");
                    assertThat(companion.getHostConfig().getReadonlyRootfs()).isTrue();
                    assertThat(companion.getHostConfig().getMemory()).isEqualTo(128L * 1024 * 1024);
                    assertThat(companion.getHostConfig().getNanoCPUs()).isEqualTo(1_000_000_000L);
                    assertThat(companion.getHostConfig().getPidsLimit()).isEqualTo(128L);
                    assertThat(companion.getHostConfig().getCapDrop()).contains(com.github.dockerjava.api.model.Capability.ALL);
                    assertThat(companion.getHostConfig().getSecurityOpts()).contains("no-new-privileges:true");
                    docker.stopContainerCmd(companion.getId()).withTimeout(2).exec();
                    assertThat(runtime.health(current).healthy()).isFalse();
                    assertInvocation(runtime, pluginId, "1");
                    runtime.enable(current, 1);
                    assertThat(runtime.health(current).healthy()).isTrue();
                    execPython(docker, pluginId, "from pathlib import Path; Path('/var/lib/artio-reference/proof').write_text('durable-proof')");
                }

                runtime.disable(pluginId);
                runtime.update(next);
                runtime.enable(next, 1);
                assertInvocation(runtime, pluginId, "2");
                if (bundle) assertPersistentProof(docker, pluginId);

                runtime.disable(pluginId);
                runtime.update(current);
                runtime.enable(current, 1);
                assertInvocation(runtime, pluginId, "1");
                if (bundle) assertPersistentProof(docker, pluginId);

                runtime.disable(pluginId);
                runtime.uninstall(pluginId);
                assertThat(runtime.health(pluginId, "/health").status()).isEqualTo("NOT_INSTALLED");
                assertThat(docker.listContainersCmd().withShowAll(true)
                        .withLabelFilter(Map.of("io.artio.extension", pluginId)).exec()).isEmpty();
                assertThat(docker.listVolumesCmd().exec().getVolumes())
                        .noneMatch(volume -> volume.getLabels() != null && pluginId.equals(volume.getLabels().get("io.artio.extension")));
            } finally {
                runtime.disable(pluginId);
                runtime.uninstall(pluginId);
                docker.removeNetworkCmd(network).exec();
            }
        }
    }

    private static void assertInvocation(DockerExtensionRuntime runtime, String pluginId, String expectedVersion) throws Exception {
        var result = runtime.invoke(pluginId, 1, "payment.provider.v1", "authorize", UUID.randomUUID().toString(),
                "{}", "{\"amount\":100}", Duration.ofSeconds(5));
        assertThat(new ObjectMapper().readTree(result.responseJson()).path("runtimeVersion").asText()).isEqualTo(expectedVersion);
    }

    private static void assertPersistentProof(DockerClient docker, String pluginId) throws Exception {
        execPython(docker, pluginId, "from pathlib import Path; assert Path('/var/lib/artio-reference/proof').read_text() == 'durable-proof'");
    }

    private static void execPython(DockerClient docker, String pluginId, String code) throws Exception {
        var command = docker.execCreateCmd(DockerExtensionRuntime.containerName(pluginId))
                .withAttachStdout(true).withAttachStderr(true).withCmd("python", "-c", code).exec();
        try (var callback = new com.github.dockerjava.core.command.ExecStartResultCallback()) {
            assertThat(docker.execStartCmd(command.getId()).exec(callback).awaitCompletion(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        Long exitCode = null;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (exitCode == null && System.nanoTime() < deadline) {
            exitCode = docker.inspectExecCmd(command.getId()).exec().getExitCodeLong();
            if (exitCode == null) Thread.sleep(25);
        }
        assertThat(exitCode).isNotNull().isZero();
    }

    private static PluginManifest manifest(String pluginId, String image, String version, boolean bundle) {
        return new PluginManifest("1", pluginId, "Reference extension", "Artio", version,
                "Local lifecycle test fixture", "0.1.0", "9.9.9", image, List.of(), List.of("payment.process"),
                Map.of(), List.of("payment.provider.v1"), "/health", "/diagnostics", PluginManifest.GeographicAvailability.global(),
                bundle ? List.of(new PluginManifest.BundleService("companion", image, 8080, "/health", Map.of(),
                        List.of(), List.of(), List.of(), 128)) : List.of(),
                bundle ? List.of("/var/lib/artio-reference") : List.of(), Map.of());
    }
}
