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
import io.artio.extensions.ExtensionRuntimePort;
import io.artio.extensions.PluginManifest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Real isolated Docker storage replicas. scripts/verify-extension-runtime.ps1 builds and removes its pinned fixture image. */
class ObjectStorageDockerIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void storesReadsAndTombstonesObjectsAcrossTwoIsolatedPluginInstances() throws Exception {
        String image = System.getenv("ARTIO_TEST_OBJECT_STORAGE_IMAGE");
        assumeTrue(image != null, "Run scripts/verify-extension-runtime.ps1 to build the pinned object-storage fixture image");
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var transport = new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig()).maxConnections(10).connectionTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(120)).build();
        try (DockerClient docker = DockerClientImpl.getInstance(config, transport)) {
            String runId = System.getenv().getOrDefault("ARTIO_TEST_RUN_ID", UUID.randomUUID().toString().replace("-", ""));
            String network = "artio-storage-it-" + UUID.randomUUID().toString().substring(0, 8);
            String pluginId = "runtime.e2e" + runId + ".storage";
            String first = "instance-" + UUID.randomUUID().toString().replace("-", "");
            String second = "instance-" + UUID.randomUUID().toString().replace("-", "");
            docker.createNetworkCmd().withName(network).withDriver("bridge")
                    .withLabels(Map.of("io.artio.test.run", runId)).exec();
            var runtime = new DockerExtensionRuntime(docker, network, 8080,
                    new ExtensionCredentialService("integration-test-key-material-that-is-at-least-32-bytes"), true);
            PluginManifest release = new PluginManifest("2", pluginId, "Object Storage Test", "Artio", "1.0.0",
                    "Isolated object storage acceptance fixture", "0.1.0", "9.0.0", image, List.of(), List.of(), Map.of(),
                    List.of(), "/health", "/diagnostics", PluginManifest.GeographicAvailability.global(), List.of(),
                    List.of("/data"), Map.of());
            try {
                runtime.installInstance(first, release);
                runtime.installInstance(second, release);
                runtime.enableInstance(first, release, 1, Map.of());
                runtime.enableInstance(second, release, 1, Map.of());
                assertThat(runtime.healthInstance(first, release).healthy()).isTrue();
                assertThat(runtime.healthInstance(second, release).healthy()).isTrue();

                byte[] bytes = "isolated durable product media".getBytes(StandardCharsets.UTF_8);
                String checksum = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                String key = UUID.randomUUID() + ".png";
                String upload = UUID.randomUUID().toString();
                for (String instance : List.of(first, second)) {
                    String putKey = UUID.randomUUID().toString();
                    var put = runtime.invoke(instance, 1, "storage.object.v1", "put", putKey, "{}",
                            MAPPER.writeValueAsString(Map.of("key", key, "uploadId", upload, "chunkIndex", 0,
                                    "totalChunks", 1, "chunkBase64", Base64.getEncoder().encodeToString(bytes),
                                    "sha256", checksum, "complete", true, "placementRole", instance.equals(first) ? "PRIMARY" : "REPLICA")),
                            Duration.ofSeconds(5));
                    assertThat(MAPPER.readTree(put.responseJson()).path("sha256").asText()).isEqualTo(checksum);
                }
                for (String instance : List.of(first, second)) {
                    var get = runtime.invoke(instance, 1, "storage.object.v1", "get", UUID.randomUUID().toString(), "{}",
                            MAPPER.writeValueAsString(Map.of("key", key, "chunkIndex", 0,
                                    "placementRole", instance.equals(first) ? "PRIMARY" : "REPLICA")), Duration.ofSeconds(5));
                    assertThat(Base64.getDecoder().decode(MAPPER.readTree(get.responseJson()).path("chunkBase64").asText())).isEqualTo(bytes);
                }

                String firstVolume = DockerExtensionRuntime.volumeName(first, "main", "/data");
                String secondVolume = DockerExtensionRuntime.volumeName(second, "main", "/data");
                assertThat(firstVolume).isNotEqualTo(secondVolume);
                var firstVolumeInfo = docker.inspectVolumeCmd(firstVolume).exec();
                var secondVolumeInfo = docker.inspectVolumeCmd(secondVolume).exec();
                assertThat(firstVolumeInfo.getLabels()).containsEntry("io.artio.extension", pluginId)
                        .containsEntry("io.artio.extension.runtime", first)
                        .containsEntry("io.artio.managed", "true");
                assertThat(secondVolumeInfo.getLabels()).containsEntry("io.artio.extension", pluginId)
                        .containsEntry("io.artio.extension.runtime", second)
                        .containsEntry("io.artio.managed", "true");

                // A plugin restart replaces its container but must retain its separately scoped data volume.
                String originalFirstContainer = docker.inspectContainerCmd(DockerExtensionRuntime.containerName(first)).exec().getId();
                runtime.disableInstance(first, pluginId);
                runtime.enableInstance(first, release, 1, Map.of());
                assertThat(runtime.healthInstance(first, release).healthy()).isTrue();
                String restartedFirstContainer = docker.inspectContainerCmd(DockerExtensionRuntime.containerName(first)).exec().getId();
                assertThat(restartedFirstContainer).isNotEqualTo(originalFirstContainer);
                assertThat(docker.inspectVolumeCmd(firstVolume).exec().getName()).isEqualTo(firstVolume);
                var persisted = runtime.invoke(first, 1, "storage.object.v1", "get", UUID.randomUUID().toString(), "{}",
                        MAPPER.writeValueAsString(Map.of("key", key, "chunkIndex", 0, "placementRole", "PRIMARY")), Duration.ofSeconds(5));
                assertThat(MAPPER.readTree(persisted.responseJson()).path("sha256").asText()).isEqualTo(checksum);

                String tombstone = UUID.randomUUID().toString();
                var delete = runtime.invoke(first, 1, "storage.object.v1", "delete", UUID.randomUUID().toString(), "{}",
                        MAPPER.writeValueAsString(Map.of("key", key, "tombstoneId", tombstone, "placementRole", "PRIMARY")),
                        Duration.ofSeconds(5));
                assertThat(MAPPER.readTree(delete.responseJson()).path("tombstoneId").asText()).isEqualTo(tombstone);
                assertThatThrownBy(() -> runtime.invoke(first, 1, "storage.object.v1", "get", UUID.randomUUID().toString(), "{}",
                        MAPPER.writeValueAsString(Map.of("key", key, "chunkIndex", 0, "placementRole", "PRIMARY")), Duration.ofSeconds(5)))
                        .isInstanceOf(ExtensionRuntimePort.ExtensionCallException.class);
                var unaffected = runtime.invoke(second, 1, "storage.object.v1", "get", UUID.randomUUID().toString(), "{}",
                        MAPPER.writeValueAsString(Map.of("key", key, "chunkIndex", 0, "placementRole", "REPLICA")), Duration.ofSeconds(5));
                assertThat(MAPPER.readTree(unaffected.responseJson()).path("sha256").asText()).isEqualTo(checksum);
            } finally {
                runtime.disableInstance(first, pluginId);
                runtime.uninstallInstance(first, pluginId);
                runtime.disableInstance(second, pluginId);
                runtime.uninstallInstance(second, pluginId);
                docker.removeNetworkCmd(network).exec();
            }
        }
    }
}
