package io.artio.api.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Volume;
import io.artio.extensions.PluginManifest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Runs the actual controller Boot JAR and production start script behind its authenticated HTTP boundary. */
@Testcontainers(disabledWithoutDocker = true)
class PackagedExtensionControllerIntegrationTests {
    private static final String TEST_KEY = "controller-integration-only-key-material-at-least-32-bytes";
    private static final String TEST_TOKEN = "controller-integration-only-bearer-token-at-least-32-bytes";

    @Test
    void packagedControllerAuthenticatesAndManagesPrivateBundleWithoutCommerceServices() throws Exception {
        String firstImage = System.getenv("ARTIO_TEST_EXTENSION_IMAGE_V1");
        String secondImage = System.getenv("ARTIO_TEST_EXTENSION_IMAGE_V2");
        assumeTrue(firstImage != null && secondImage != null, "Run scripts/verify-extension-runtime.ps1 for pinned fixture images");
        Path jar = Path.of(System.getProperty("artio.test.controller-jar"));
        assertThat(Files.isRegularFile(jar)).isTrue();
        Path script = Path.of("..", "artio-docker", "start-api.sh").toAbsolutePath().normalize();
        String identity = UUID.randomUUID().toString().replace("-", "");
        String network = "artio-controller-it-" + identity;
        String controlNetwork = "artio-controller-control-it-" + identity;
        String secrets = "artio-controller-secrets-it-" + identity;
        String pluginId = "controller.e2e" + identity;
        var docker = DockerClientFactory.instance().client();
        docker.createNetworkCmd().withName(network).withInternal(true).exec();
        docker.createNetworkCmd().withName(controlNetwork).exec();
        var volume = docker.createVolumeCmd().withName(secrets).withLabels(Map.of("io.artio.test.fixture", identity)).exec();
        assertThat(volume.getLabels()).containsEntry("io.artio.test.fixture", identity);
        var image = new ImageFromDockerfile("artio/controller-it:" + identity, true)
                .withFileFromPath("controller.jar", jar)
                .withFileFromPath("start-api.sh", script)
                .withFileFromString("Dockerfile", """
                        FROM eclipse-temurin:21-jre
                        WORKDIR /app
                        COPY controller.jar /app/extension-controller.jar
                        COPY start-api.sh /app/start-api.sh
                        RUN chmod 0755 /app/start-api.sh
                        ENTRYPOINT ["/app/start-api.sh"]
                        """);
        try {
            try (var seed = new GenericContainer<>("alpine:3.20")
                    .withCopyToContainer(Transferable.of((TEST_KEY + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8), 0600), "/seed/encryption-key")
                    .withCopyToContainer(Transferable.of((TEST_KEY + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8), 0600), "/seed/extension-auth-key")
                    .withCopyToContainer(Transferable.of((TEST_TOKEN + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8), 0600), "/seed/extension-controller-token")
                    .withCreateContainerCmdModifier(command -> command.getHostConfig()
                            .withBinds(new Bind(secrets, new Volume("/secrets"))))
                    .withCommand("sh", "-ec", "cp /seed/* /secrets/; chmod 700 /secrets; chmod 600 /secrets/*")
                    .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(30)))) {
                seed.start();
            }
            try (var controller = new GenericContainer<>(image)
                    .withNetworkMode(controlNetwork).withExposedPorts(8090)
                    .withEnv("ARTIO_RUNTIME_ROLE", "extension-controller")
                    .withEnv("ARTIO_EXTENSION_DOCKER_ENABLED", "true")
                    .withEnv("ARTIO_EXTENSION_DOCKER_MODE", "controller")
                    .withEnv("ARTIO_EXTENSION_DOCKER_NETWORK", network)
                    .withEnv("ARTIO_EXTENSIONS_DOCKER_EGRESS_PROXY", "")
                    .withEnv("PORT", "8090")
                    .withCreateContainerCmdModifier(command -> {
                        var ports = new com.github.dockerjava.api.model.Ports();
                        ports.bind(com.github.dockerjava.api.model.ExposedPort.tcp(8090),
                                com.github.dockerjava.api.model.Ports.Binding.bindIp("127.0.0.1"));
                        command.getHostConfig().withPortBindings(ports)
                            .withReadonlyRootfs(true).withCapDrop(Capability.ALL)
                            .withSecurityOpts(List.of("no-new-privileges:true"))
                            .withTmpFs(Map.of("/tmp", "rw,noexec,nosuid,size=64m"))
                            .withBinds(new Bind("/var/run/docker.sock", new Volume("/var/run/docker.sock"), AccessMode.rw),
                                    new Bind(secrets, new Volume("/run/artio-controller-secrets"), AccessMode.ro));
                    })
                    .waitingFor(Wait.forHttp("/actuator/health").forPort(8090))
                    .withStartupTimeout(Duration.ofSeconds(90))) {
                controller.start();
                docker.connectToNetworkCmd().withNetworkId(network).withContainerId(controller.getContainerId()).exec();
                String base = "http://" + controller.getHost() + ":" + controller.getMappedPort(8090) + "/";
                var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
                var mapper = new ObjectMapper().findAndRegisterModules();
                var current = manifest(pluginId, firstImage, "1.0.0");
                var next = manifest(pluginId, secondImage, "2.0.0");
                var denied = http.send(HttpRequest.newBuilder(URI.create(base + "internal/v1/extensions/install"))
                        .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("manifest", current))))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertThat(denied.statusCode()).isEqualTo(401);
                assertThat(http.send(HttpRequest.newBuilder(URI.create(base + "internal/v1/extensions/install"))
                        .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{malformed"))
                        .build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
                byte[] oversized = new byte[8 * 1024 * 1024 + 1];
                // Send only headers with Expect: 100-continue: fixed lengths must be rejected before any body upload.
                try (var socket = new java.net.Socket(controller.getHost(), controller.getMappedPort(8090))) {
                    socket.setSoTimeout(10000);
                    socket.getOutputStream().write(("POST /internal/v1/extensions/install HTTP/1.1\r\n"
                            + "Host: localhost\r\nAuthorization: Bearer " + TEST_TOKEN
                            + "\r\nContent-Type: application/json\r\nContent-Length: " + oversized.length
                            + "\r\nExpect: 100-continue\r\nConnection: close\r\n\r\n")
                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    var response = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),
                            java.nio.charset.StandardCharsets.US_ASCII));
                    assertThat(response.readLine()).contains("413");
                }
                var chunked = HttpRequest.BodyPublishers.ofInputStream(
                        () -> new java.io.ByteArrayInputStream(oversized));
                assertThat(http.send(HttpRequest.newBuilder(URI.create(base + "internal/v1/extensions/install"))
                        .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + TEST_TOKEN).POST(chunked)
                        .build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(413);
                for (String route : List.of("graphql", "api/products", "v3/api-docs")) {
                    assertThat(http.send(HttpRequest.newBuilder(URI.create(base + route)).timeout(Duration.ofSeconds(5))
                            .GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()).as(route).isEqualTo(404);
                }
                var remote = new RemoteExtensionRuntime(mapper, base, TEST_TOKEN);
                try {
                    remote.install(current);
                    remote.enable(current, 1);
                    assertThat(remote.health(current).healthy()).isTrue();
                    assertInvocation(remote, mapper, pluginId, 1, "1");
                    var primary = docker.inspectContainerCmd(DockerExtensionRuntime.containerName(pluginId)).exec();
                    var sidecar = docker.inspectContainerCmd(DockerExtensionRuntime.serviceName(pluginId, "companion")).exec();
                    assertThat(primary.getHostConfig().getPortBindings().getBindings()).isEmpty();
                    assertThat(sidecar.getHostConfig().getPortBindings().getBindings()).isEmpty();
                    remote.rotateCredentials(current, 2, true, Map.of());
                    assertInvocation(remote, mapper, pluginId, 2, "1");
                    remote.disable(pluginId);
                    remote.update(next);
                    remote.enable(next, 2);
                    assertInvocation(remote, mapper, pluginId, 2, "2");
                    remote.disable(pluginId);
                    remote.update(current);
                    remote.enable(current, 2);
                    assertInvocation(remote, mapper, pluginId, 2, "1");
                    remote.disable(pluginId);
                    remote.uninstall(pluginId);
                    assertThat(remote.health(pluginId, "/health").status()).isEqualTo("NOT_INSTALLED");
                } finally {
                    remote.disable(pluginId);
                    remote.uninstall(pluginId);
                }
            }
        } finally {
            // Only resources bearing this freshly generated fixture identity can be removed.
            for (var container : docker.listContainersCmd().withShowAll(true)
                    .withLabelFilter(Map.of("io.artio.extension", pluginId, "io.artio.managed", "true")).exec()) {
                docker.removeContainerCmd(container.getId()).withForce(true).exec();
            }
            docker.removeNetworkCmd(network).exec();
            docker.removeNetworkCmd(controlNetwork).exec();
            assertThat(docker.inspectVolumeCmd(secrets).exec().getLabels()).containsEntry("io.artio.test.fixture", identity);
            docker.removeVolumeCmd(secrets).exec();
        }
    }

    private static PluginManifest manifest(String id, String image, String version) {
        return new PluginManifest("1", id, "Controller fixture", "Artio", version, "Packaged HTTP lifecycle proof",
                "0.1.0", "9.9.9", image, List.of(), List.of("payment.process"), Map.of(),
                List.of("payment.provider.v1"), "/health", "/diagnostics", PluginManifest.GeographicAvailability.global(),
                List.of(new PluginManifest.BundleService("companion", image, 8080, "/health", Map.of())));
    }

    private static void assertInvocation(RemoteExtensionRuntime remote, ObjectMapper mapper, String pluginId,
            int generation, String version) throws Exception {
        var result = remote.invoke(pluginId, generation, "payment.provider.v1", "authorize", UUID.randomUUID().toString(),
                "{}", "{}", Duration.ofSeconds(5));
        assertThat(mapper.readTree(result.responseJson()).path("runtimeVersion").asText()).isEqualTo(version);
    }
}
