package io.artio.api.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.HostConfig;
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
    void inspectsDockerSaveArchiveAndImportsItsVerifiedConfigIntoLocalCache() throws Exception {
        String archiveFile = System.getenv("ARTIO_LOCAL_ARCHIVE_TEST_FILE");
        String imageReferenceDigest = System.getenv("ARTIO_LOCAL_ARCHIVE_TEST_REFERENCE_DIGEST");
        assumeTrue(archiveFile != null && imageReferenceDigest != null,
                "Run scripts/verify-local-archive-import.ps1 to provide the Docker-save fixture");
        var manifest = new PluginManifest("1", "archive.acceptance", "Archive Acceptance", "Artio", "1.0.0",
                "Local archive runtime acceptance", "0.1.0", "9.0.0",
                "artio-local-archive-acceptance@" + imageReferenceDigest,
                List.of(), List.of(), Map.of(), List.of(), "/health", "/diagnostics");
        var archive = java.nio.file.Path.of(archiveFile);
        var inspection = new OciArchiveInspector(OciArchiveInspector.Limits.developmentDefaults())
                .inspect(archive, manifest);
        assertThat(inspection.sourceFormat()).isEqualTo("DOCKER_SAVE");
        var inventory = new java.util.LinkedHashMap<String, List<String>>();
        inspection.images().forEach((digest, image) -> inventory.put(digest, image.configDigests()));
        String alias = "artio-local-cache:ref-" + imageReferenceDigest.replace(':', '-');

        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var transport = new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig()).maxConnections(4).connectionTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(120)).build();
        try (DockerClient docker = DockerClientImpl.getInstance(config, transport)) {
            var runtime = new DockerExtensionRuntime(docker, "artio-extensions", 8080,
                    new ExtensionCredentialService("integration-test-key-material-that-is-at-least-32-bytes"));
            try {
                runtime.importLocalArchive(archive, inventory);
                runtime.install(manifest);
                assertThat(docker.inspectImageCmd(alias).exec().getId()).isEqualTo(inspection.images()
                        .get(imageReferenceDigest).configDigests().getFirst());
            } finally {
                try { docker.removeImageCmd(alias).withForce(false).exec(); }
                catch (com.github.dockerjava.api.exception.NotFoundException ignored) { }
            }
        }
    }

    @Test
    void installsInvokesUpdatesAndRollsBackRealRestrictedContainers() throws Exception {
        verifyLifecycle(false);
    }

    @Test
    void managesSidecarHealthAndPreservesDataAcrossBundleUpdateAndRollback() throws Exception {
        verifyLifecycle(true);
    }

    @Test
    void deliversRotatedSecretsOnlyThroughPrivateTmpfsFiles() throws Exception {
        String image = System.getenv("ARTIO_TEST_EXTENSION_IMAGE_V1");
        assumeTrue(image != null, "Run scripts/verify-extension-runtime.ps1 to provide the pinned fixture image");
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var transport = new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig()).maxConnections(6).connectionTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(120)).build();
        try (DockerClient docker = DockerClientImpl.getInstance(config, transport)) {
            String network = "artio-secrets-it-" + UUID.randomUUID().toString().substring(0, 8);
            String pluginId = testPluginId("secret.e2e");
            docker.createNetworkCmd().withName(network).withDriver("bridge")
                    .withLabels(Map.of("io.artio.test.run", testRunId())).exec();
            var runtime = new DockerExtensionRuntime(docker, network, 8080,
                    new ExtensionCredentialService("integration-test-key-material-that-is-at-least-32-bytes"), true);
            PluginManifest base = manifest(pluginId, image, "1.0.0", false);
            var secretField = new PluginManifest.ConfigField("secret", true, true, "Acceptance secret", null,
                    null, List.of(), 1, 100, null, null);
            var release = new PluginManifest(base.manifestVersion(), base.id(), base.name(), base.vendor(), base.version(),
                    base.description(), base.minimumCoreVersion(), base.maximumCoreVersion(), base.image(), base.dependencies(),
                    base.permissions(), Map.of("acceptanceSecret", secretField), base.extensionPoints(), base.healthPath(),
                    base.diagnosticsPath(), base.geographicAvailability(), base.services(), base.persistentPaths(),
                    base.configurationBindings(), Map.of("/run/secrets/acceptance-secret", "acceptanceSecret"));
            try {
                runtime.install(release);
                runtime.enable(release, 1, Map.of("acceptanceSecret", "first-private-value"));
                String containerName = DockerExtensionRuntime.containerName(pluginId);
                var first = docker.inspectContainerCmd(containerName).exec();
                assertThat(java.util.Arrays.stream(first.getConfig().getEnv()))
                        .noneMatch(value -> value.contains("first-private-value"));
                var secretBind = java.util.Arrays.stream(first.getHostConfig().getBinds())
                        .filter(bind -> "/run/secrets".equals(bind.getVolume().getPath())).findFirst().orElseThrow();
                assertThat(secretBind.getAccessMode()).isEqualTo(com.github.dockerjava.api.model.AccessMode.ro);
                assertThat(docker.inspectVolumeCmd(secretBind.getPath()).exec().getOptions()).containsEntry("type", "tmpfs");
                execPython(docker, pluginId, "from pathlib import Path; import os, stat; p=Path('/run/secrets/acceptance-secret'); assert p.read_text() == 'first-private-value'; assert stat.S_IMODE(p.stat().st_mode) == 0o600; assert p.stat().st_uid == 10001; assert p.stat().st_gid == 10001");

                runtime.disable(pluginId);
                runtime.enable(release, 2, Map.of("acceptanceSecret", "rotated-private-value"));
                var rotated = docker.inspectContainerCmd(containerName).exec();
                assertThat(rotated.getId()).isNotEqualTo(first.getId());
                assertThat(java.util.Arrays.stream(rotated.getConfig().getEnv()))
                        .noneMatch(value -> value.contains("rotated-private-value"));
                execPython(docker, pluginId, "from pathlib import Path; p=Path('/run/secrets/acceptance-secret'); assert p.read_text() == 'rotated-private-value'; assert 'first-private-value' not in p.read_text() ");
            } finally {
                runtime.disable(pluginId);
                runtime.uninstall(pluginId);
                docker.removeNetworkCmd(network).exec();
            }
        }
    }

    @Test
    void isolatesTwoLiveInstancesOfTheSameReleaseAndLifecycle() throws Exception {
        String image = System.getenv("ARTIO_TEST_EXTENSION_IMAGE_V1");
        assumeTrue(image != null, "Run scripts/verify-extension-runtime.ps1 to provide the pinned fixture image");
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var transport = new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig()).maxConnections(10).connectionTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(120)).build();
        try (DockerClient docker = DockerClientImpl.getInstance(config, transport)) {
            String network = "artio-multi-it-" + UUID.randomUUID().toString().substring(0, 8);
            String pluginId = testPluginId("runtime.e2e");
            String instanceOne = "instance-" + UUID.randomUUID().toString().replace("-", "");
            String instanceTwo = "instance-" + UUID.randomUUID().toString().replace("-", "");
            docker.createNetworkCmd().withName(network).withDriver("bridge").withLabels(Map.of("io.artio.test.run", testRunId())).exec();
            var runtime = new DockerExtensionRuntime(docker, network, 8080,
                    new ExtensionCredentialService("integration-test-key-material-that-is-at-least-32-bytes"), true);
            PluginManifest release = manifest(pluginId, image, "1.0.0", false);
            try {
                runtime.installInstance(instanceOne, release);
                runtime.installInstance(instanceTwo, release);
                runtime.enableInstance(instanceOne, release, 3, Map.of());
                runtime.enableInstance(instanceTwo, release, 7, Map.of());

                var firstContainer = docker.inspectContainerCmd(DockerExtensionRuntime.containerName(instanceOne)).exec();
                var secondContainer = docker.inspectContainerCmd(DockerExtensionRuntime.containerName(instanceTwo)).exec();
                assertThat(firstContainer.getId()).isNotEqualTo(secondContainer.getId());
                assertThat(firstContainer.getConfig().getLabels()).containsEntry("io.artio.extension", pluginId)
                        .containsEntry("io.artio.extension.runtime", instanceOne);
                assertThat(secondContainer.getConfig().getLabels()).containsEntry("io.artio.extension", pluginId)
                        .containsEntry("io.artio.extension.runtime", instanceTwo);
                assertThat(environment(firstContainer, "ARTIO_EXTENSION_TOKEN"))
                        .isNotEqualTo(environment(secondContainer, "ARTIO_EXTENSION_TOKEN"));
                assertThat(runtime.healthInstance(instanceOne, release).healthy()).isTrue();
                assertThat(runtime.healthInstance(instanceTwo, release).healthy()).isTrue();
                assertInvocation(runtime, instanceOne, 3, "1");
                assertInvocation(runtime, instanceTwo, 7, "1");

                runtime.disableInstance(instanceOne, pluginId);
                assertThat(runtime.healthInstance(instanceOne, release).healthy()).isFalse();
                assertThat(runtime.healthInstance(instanceTwo, release).healthy()).isTrue();
                runtime.uninstallInstance(instanceOne, pluginId);
                assertThat(docker.listContainersCmd().withShowAll(true).withLabelFilter(
                        Map.of("io.artio.extension.runtime", instanceOne)).exec()).isEmpty();
                assertThat(runtime.healthInstance(instanceTwo, release).healthy()).isTrue();
            } finally {
                runtime.disableInstance(instanceOne, pluginId);
                runtime.uninstallInstance(instanceOne, pluginId);
                runtime.disableInstance(instanceTwo, pluginId);
                runtime.uninstallInstance(instanceTwo, pluginId);
                docker.removeNetworkCmd(network).exec();
            }
        }
    }

    @Test
    void restrictsRealPluginEgressToTheProviderGateway() throws Exception {
        String extensionImage = System.getenv("ARTIO_TEST_EXTENSION_IMAGE_V1");
        String proxyImage = System.getenv("ARTIO_TEST_EGRESS_IMAGE");
        assumeTrue(extensionImage != null && proxyImage != null,
                "Run scripts/verify-extension-runtime.ps1 to provide pinned extension and egress images");

        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var transport = new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig()).maxConnections(10).connectionTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(120)).build();
        try (DockerClient docker = DockerClientImpl.getInstance(config, transport)) {
            String privateNetwork = "artio-egress-it-" + UUID.randomUUID().toString().substring(0, 8);
            String outboundNetwork = privateNetwork + "-out";
            String pluginId = testPluginId("runtime.egress");
            String proxyName = "artio-egress-" + UUID.randomUUID().toString().substring(0, 8);
            docker.createNetworkCmd().withName(privateNetwork).withDriver("bridge").withInternal(true)
                    .withLabels(Map.of("io.artio.test.run", testRunId())).exec();
            docker.createNetworkCmd().withName(outboundNetwork).withDriver("bridge")
                    .withLabels(Map.of("io.artio.test.run", testRunId())).exec();
            var runtime = new DockerExtensionRuntime(docker, privateNetwork, 8080,
                    new ExtensionCredentialService("integration-test-key-material-that-is-at-least-32-bytes"), true);
            PluginManifest plugin = manifest(pluginId, extensionImage, "1.0.0", false);
            String proxyContainer = null;
            try {
                docker.pullImageCmd(proxyImage).start().awaitCompletion(120, java.util.concurrent.TimeUnit.SECONDS);
                proxyContainer = docker.createContainerCmd(proxyImage).withName(proxyName)
                        .withHostConfig(HostConfig.newHostConfig().withNetworkMode(outboundNetwork)
                                .withMemory(128L * 1024 * 1024).withNanoCPUs(500_000_000L).withPidsLimit(64L)
                                .withReadonlyRootfs(true).withCapDrop(com.github.dockerjava.api.model.Capability.ALL)
                                .withSecurityOpts(List.of("no-new-privileges:true")))
                        .withUser("10001:10001").withEnv("ARTIO_EGRESS_ALLOWED_HOSTS=api.stripe.com")
                        .withLabels(Map.of("io.artio.test.egress", testRunId())).exec().getId();
                docker.startContainerCmd(proxyContainer).exec();
                docker.connectToNetworkCmd().withContainerId(proxyContainer).withNetworkId(privateNetwork)
                        .withContainerNetwork(new ContainerNetwork().withAliases("extension-egress")).exec();

                runtime.install(plugin);
                var extensionCredentials = new ExtensionCredentialService("integration-test-key-material-that-is-at-least-32-bytes");
                var extensionEnvironment = DockerExtensionRuntime.resolvedEnvironment(plugin, true, Map.of(), 1,
                        Map.of(), extensionCredentials);
                docker.createContainerCmd(extensionImage).withName(DockerExtensionRuntime.containerName(pluginId))
                        .withHostConfig(DockerExtensionRuntime.restrictedHostConfig(privateNetwork))
                        .withUser("10001:10001").withExposedPorts(com.github.dockerjava.api.model.ExposedPort.tcp(8080))
                        .withEnv(extensionEnvironment)
                        .withLabels(Map.of("io.artio.extension", pluginId, "io.artio.extension.service", "main",
                                "io.artio.managed", "true")).exec();
                docker.startContainerCmd(DockerExtensionRuntime.containerName(pluginId)).exec();
                execPython(docker, pluginId, """
                        import socket
                        proxy = socket.create_connection(('extension-egress', 3128), 5)
                        proxy.sendall(b'CONNECT api.stripe.com:443 HTTP/1.1\\r\\nHost: api.stripe.com:443\\r\\n\\r\\n')
                        response = proxy.recv(512)
                        assert b'200 Connection Established' in response, response
                        proxy.close()
                        for target in (b'attacker.example:443', b'api.stripe.com:80'):
                            denied = socket.create_connection(('extension-egress', 3128), 5)
                            denied.sendall(b'CONNECT ' + target + b' HTTP/1.1\\r\\nHost: ' + target + b'\\r\\n\\r\\n')
                            response = denied.recv(512)
                            denied.close()
                            assert b'403 Destination Not Allowed' in response, response
                        try:
                            socket.create_connection(('api.stripe.com', 443), 2)
                            raise AssertionError('plugin had direct outbound access')
                        except OSError:
                            pass
                        """);
                assertThat(docker.inspectContainerCmd(DockerExtensionRuntime.containerName(pluginId)).exec()
                        .getState().getRunning()).isTrue();
                docker.stopContainerCmd(proxyContainer).withTimeout(2).exec();
                execPython(docker, pluginId, """
                        import socket
                        try:
                            socket.create_connection(('extension-egress', 3128), 3)
                            raise AssertionError('gateway outage unexpectedly allowed a connection')
                        except OSError:
                            pass
                        """);
            } finally {
                runtime.disable(pluginId);
                runtime.uninstall(pluginId);
                if (proxyContainer != null) {
                    try { docker.removeContainerCmd(proxyContainer).withForce(true).exec(); }
                    catch (com.github.dockerjava.api.exception.NotFoundException ignored) { }
                }
                docker.removeNetworkCmd(privateNetwork).exec();
                docker.removeNetworkCmd(outboundNetwork).exec();
            }
        }
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
            String pluginId = testPluginId("runtime.e2e");
            // The test host is outside Docker's private network, so this harness uses loopback port forwarding.
            // Production uses the private, unpublished extension network and is separately asserted by runtime policy tests.
            docker.createNetworkCmd().withName(network).withDriver("bridge").withLabels(Map.of("io.artio.test.run", testRunId())).exec();
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

    private static void assertInvocation(DockerExtensionRuntime runtime, String runtimeIdentity, int credentialGeneration,
            String expectedVersion) throws Exception {
        var result = runtime.invoke(runtimeIdentity, credentialGeneration, "payment.provider.v1", "authorize",
                UUID.randomUUID().toString(), "{}", "{\"amount\":100}", Duration.ofSeconds(5));
        assertThat(new ObjectMapper().readTree(result.responseJson()).path("runtimeVersion").asText()).isEqualTo(expectedVersion);
    }

    private static String environment(com.github.dockerjava.api.command.InspectContainerResponse container, String key) {
        return java.util.Arrays.stream(container.getConfig().getEnv()).filter(value -> value.startsWith(key + "="))
                .map(value -> value.substring(key.length() + 1)).findFirst().orElseThrow();
    }

    private static String testPluginId(String prefix) {
        return prefix + testRunId() + UUID.randomUUID().toString().replace("-", "");
    }

    private static String testRunId() {
        String runId = System.getenv("ARTIO_TEST_RUN_ID");
        return runId == null || runId.isBlank() ? "manual" : runId;
    }

    private static void assertPersistentProof(DockerClient docker, String pluginId) throws Exception {
        execPython(docker, pluginId, "from pathlib import Path; assert Path('/var/lib/artio-reference/proof').read_text() == 'durable-proof'");
    }

    private static void execPython(DockerClient docker, String pluginId, String code) throws Exception {
        var command = docker.execCreateCmd(DockerExtensionRuntime.containerName(pluginId))
                .withAttachStdout(true).withAttachStderr(true).withCmd("python", "-c", code).exec();
        var stdout = new java.io.ByteArrayOutputStream();
        var stderr = new java.io.ByteArrayOutputStream();
        try (var callback = new com.github.dockerjava.core.command.ExecStartResultCallback(stdout, stderr)) {
            assertThat(docker.execStartCmd(command.getId()).exec(callback).awaitCompletion(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        Long exitCode = null;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (exitCode == null && System.nanoTime() < deadline) {
            exitCode = docker.inspectExecCmd(command.getId()).exec().getExitCodeLong();
            if (exitCode == null) Thread.sleep(25);
        }
        assertThat(exitCode).as("plugin exec failed; stdout=%s stderr=%s", stdout.toString(java.nio.charset.StandardCharsets.UTF_8),
                stderr.toString(java.nio.charset.StandardCharsets.UTF_8)).isNotNull().isZero();
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
