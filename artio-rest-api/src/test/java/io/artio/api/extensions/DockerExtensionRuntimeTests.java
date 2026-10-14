package io.artio.api.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.NetworkSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DockerExtensionRuntimeTests {
    @Test
    void containerNamesAreSafeBoundedAndCollisionResistant() {
        String dotted = DockerExtensionRuntime.containerName("payments.stripe");
        String dashed = DockerExtensionRuntime.containerName("payments-stripe");
        assertThat(dotted).startsWith("artio-ext-payments-stripe-").hasSizeLessThanOrEqualTo(63);
        assertThat(dashed).isNotEqualTo(dotted);
        assertThatIllegalArgumentException().isThrownBy(() -> DockerExtensionRuntime.containerName("../host"));
    }

    @Test
    void invocationPayloadMustFitWithinOneMiB() {
        io.artio.extensions.ExtensionRuntimePort.validateInvocation("payment.provider.v1", "authorize",
                "00000000-0000-0000-0000-000000000001", "{}", "small", Duration.ofSeconds(1));
        assertThatIllegalArgumentException().isThrownBy(() -> io.artio.extensions.ExtensionRuntimePort.validateInvocation(
                "payment.provider.v1", "authorize", "00000000-0000-0000-0000-000000000001", "{}", "x".repeat(1_048_577), Duration.ofSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> io.artio.extensions.ExtensionRuntimePort.validateInvocation(
                "payment.provider.v1", "authorize", "00000000-0000-0000-0000-000000000001", "{}", "x".repeat(1_048_576), Duration.ofSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> io.artio.extensions.ExtensionRuntimePort.validateInvocation(
                "payment.provider.v1", "authorize", "00000000-0000-0000-0000-000000000001", "{}", "", Duration.ofSeconds(1)));
    }

    @Test
    void containersReceiveOnlyTheRestrictedRuntimePolicy() {
        var host = DockerExtensionRuntime.restrictedHostConfig("artio-extensions");
        assertThat(host.getNetworkMode()).isEqualTo("artio-extensions");
        assertThat(host.getMemory()).isEqualTo(268_435_456L);
        assertThat(host.getNanoCPUs()).isEqualTo(1_000_000_000L);
        assertThat(host.getPidsLimit()).isEqualTo(128L);
        assertThat(host.getReadonlyRootfs()).isTrue();
        assertThat(host.getPortBindings()).isNull();
        assertThat(host.getBinds()).isEmpty();
        assertThat(host.getCapDrop()).containsExactly(com.github.dockerjava.api.model.Capability.ALL);
        assertThat(host.getSecurityOpts()).contains("no-new-privileges:true");
        assertThat(host.getTmpFs()).containsEntry("/tmp", "rw,noexec,nosuid,size=16m");
    }

    @Test
    void enablesBundleOnlyAfterEveryServicePassesItsHealthCheck() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var healthCalls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.createContext("/health", exchange -> { healthCalls.add("main"); exchange.sendResponseHeaders(204, -1); exchange.close(); });
        server.createContext("/metrics-health", exchange -> { healthCalls.add("metrics"); exchange.sendResponseHeaders(204, -1); exchange.close(); });
        server.start();
        String pluginId = "observability.bundle";
        String mainName = DockerExtensionRuntime.containerName(pluginId);
        String serviceName = DockerExtensionRuntime.serviceName(pluginId, "metrics");
        try {
            DockerClient docker = mock(DockerClient.class);
            var mainCommand = mock(InspectContainerCmd.class);
            var serviceCommand = mock(InspectContainerCmd.class);
            var mainContainer = runningContainer(pluginId, "main", server.getAddress().getPort());
            var serviceContainer = runningContainer(pluginId, "metrics", server.getAddress().getPort());
            when(docker.inspectContainerCmd(mainName)).thenReturn(mainCommand);
            when(docker.inspectContainerCmd(serviceName)).thenReturn(serviceCommand);
            when(mainCommand.exec()).thenReturn(mainContainer);
            when(serviceCommand.exec()).thenReturn(serviceContainer);

            var manifest = new io.artio.extensions.PluginManifest("1", pluginId, "Observability", "Artio", "1.0.0", "Metrics bundle",
                    "0.1.0", "1.0.0", "ghcr.io/artio/observability@sha256:" + "a".repeat(64), java.util.List.of(),
                    java.util.List.of(), java.util.Map.of(), java.util.List.of(), "/health", "/diagnostics",
                    io.artio.extensions.PluginManifest.GeographicAvailability.global(),
                    java.util.List.of(new io.artio.extensions.PluginManifest.BundleService("metrics",
                            "ghcr.io/artio/metrics@sha256:" + "b".repeat(64), server.getAddress().getPort(), "/metrics-health")));
            var runtime = new DockerExtensionRuntime(docker, "extensions", server.getAddress().getPort(),
                    new io.artio.api.security.ExtensionCredentialService("unit-test-key-material-that-is-at-least-32-bytes"));

            runtime.enable(manifest, 1);

            assertThat(healthCalls).containsExactlyInAnyOrder("main", "metrics");
        } finally {
            server.stop(0);
        }
    }

    private static InspectContainerResponse runningContainer(String pluginId, String serviceId, int port) {
        var container = mock(InspectContainerResponse.class);
        var state = mock(InspectContainerResponse.ContainerState.class);
        var config = mock(com.github.dockerjava.api.model.ContainerConfig.class);
        var settings = mock(NetworkSettings.class);
        var network = mock(ContainerNetwork.class);
        when(container.getId()).thenReturn("container-" + serviceId);
        when(container.getState()).thenReturn(state);
        when(state.getRunning()).thenReturn(true);
        when(container.getConfig()).thenReturn(config);
        when(config.getLabels()).thenReturn(Map.of("io.artio.extension", pluginId, "io.artio.managed", "true", "io.artio.extension.service", serviceId));
        when(config.getExposedPorts()).thenReturn(new com.github.dockerjava.api.model.ExposedPort[] { com.github.dockerjava.api.model.ExposedPort.tcp(port) });
        when(container.getNetworkSettings()).thenReturn(settings);
        when(settings.getNetworks()).thenReturn(Map.of("extensions", network));
        when(network.getIpAddress()).thenReturn("127.0.0.1");
        return container;
    }

    @Test
    void invocationEnvelopeHasStrictBoundsAndStableRequestIdentity() {
        io.artio.extensions.ExtensionRuntimePort.validateInvocation("payment.provider.v1", "authorize", "00000000-0000-0000-0000-000000000001",
                "{}", "{\"amount\":100}", java.time.Duration.ofSeconds(5));
        assertThatIllegalArgumentException().isThrownBy(() -> io.artio.extensions.ExtensionRuntimePort.validateInvocation("payment.provider.v1",
                "authorize", "invalid", "{}", "{}", java.time.Duration.ofSeconds(5)));
        assertThatIllegalArgumentException().isThrownBy(() -> io.artio.extensions.ExtensionRuntimePort.validateInvocation("payment.provider.v1",
                "authorize", "1-1-1-1-1", "{}", "{}", java.time.Duration.ofSeconds(5)));
        assertThatIllegalArgumentException().isThrownBy(() -> io.artio.extensions.ExtensionRuntimePort.validateInvocation("payment.provider.v1",
                "authorize", "00000000-0000-0000-0000-000000000001", "{}", "{}", java.time.Duration.ofSeconds(31)));
        assertThatIllegalArgumentException().isThrownBy(() -> io.artio.extensions.ExtensionRuntimePort.validateInvocation("../../host",
                "authorize", "00000000-0000-0000-0000-000000000001", "{}", "{}", java.time.Duration.ofSeconds(5)));
    }

    @Test
    void extensionCredentialsAreStableAndScopedToThePlugin() {
        var credentials = new io.artio.api.security.ExtensionCredentialService("unit-test-key-material-that-is-at-least-32-bytes");
        String token = credentials.derive("payments.example");
        assertThat(token).isEqualTo(credentials.derive("payments.example"));
        assertThat(token).isNotEqualTo(credentials.derive("shipping.example"));
        assertThat(token).isNotEqualTo(credentials.derive("payments.example", 2));
        assertThat(credentials.derive("payments.example", 1)).isEqualTo(token);
        assertThat(token).hasSize(43);
    }

    @Test
    void invocationUsesAuthenticatedVersionedEnvelopeAndBoundedHttpTransport() throws Exception {
        var encryption = new io.artio.api.security.EncryptionService("unit-test-key-material-that-is-at-least-32-bytes");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> apiVersion = new AtomicReference<>();
        AtomicReference<String> idempotencyKey = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/v1/invoke", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            apiVersion.set(exchange.getRequestHeaders().getFirst("X-Artio-Extension-Api"));
            idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"authorized\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            DockerClient docker = mock(DockerClient.class);
            InspectContainerCmd inspect = mock(InspectContainerCmd.class);
            InspectContainerResponse container = mock(InspectContainerResponse.class);
            InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
            NetworkSettings settings = mock(NetworkSettings.class);
            ContainerNetwork network = mock(ContainerNetwork.class);
            when(docker.inspectContainerCmd(anyString())).thenReturn(inspect);
            when(inspect.exec()).thenReturn(container);
            when(container.getState()).thenReturn(state);
            when(state.getRunning()).thenReturn(true);
            when(container.getNetworkSettings()).thenReturn(settings);
            when(settings.getNetworks()).thenReturn(Map.of("extensions", network));
            when(network.getIpAddress()).thenReturn("127.0.0.1");

            var credentials = new io.artio.api.security.ExtensionCredentialService("unit-test-key-material-that-is-at-least-32-bytes");
            var runtime = new DockerExtensionRuntime(docker, "extensions", server.getAddress().getPort(), credentials);
            String key = "00000000-0000-0000-0000-000000000001";
            var result = runtime.invoke("payments.example", 2, "payment.provider.v1", "authorize", key,
                    "{\"apiKey\":\"provider-secret\"}", "{\"amount\":125}", Duration.ofSeconds(2));

            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(new ObjectMapper().readTree(result.responseJson()).get("authorized").asBoolean()).isTrue();
            assertThat(authorization.get()).isEqualTo("Bearer " + credentials.derive("payments.example", 2));
            assertThat(apiVersion.get()).isEqualTo("1");
            assertThat(idempotencyKey.get()).isEqualTo(key);
            JsonNode envelope = new ObjectMapper().readTree(body.get());
            assertThat(envelope.get("protocolVersion").asInt()).isEqualTo(1);
            assertThat(envelope.get("extensionPoint").asText()).isEqualTo("payment.provider.v1");
            assertThat(envelope.get("operation").asText()).isEqualTo("authorize");
            assertThat(envelope.get("idempotencyKey").asText()).isEqualTo(key);
            assertThat(envelope.at("/configuration/apiKey").asText()).isEqualTo("provider-secret");
            assertThat(envelope.get("payload").get("amount").asInt()).isEqualTo(125);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesOneTransientGatewayFailureWithTheSameIdempotencyKey() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<String> firstKey = new AtomicReference<>();
        AtomicReference<String> secondKey = new AtomicReference<>();
        server.createContext("/v1/invoke", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            if (attempts.incrementAndGet() == 1) {
                firstKey.set(key);
                exchange.sendResponseHeaders(503, -1);
            } else {
                secondKey.set(key);
                byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
            exchange.close();
        });
        server.start();
        try {
            DockerClient docker = mock(DockerClient.class);
            InspectContainerCmd inspect = mock(InspectContainerCmd.class);
            InspectContainerResponse container = mock(InspectContainerResponse.class);
            InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
            NetworkSettings settings = mock(NetworkSettings.class);
            ContainerNetwork network = mock(ContainerNetwork.class);
            when(docker.inspectContainerCmd(anyString())).thenReturn(inspect);
            when(inspect.exec()).thenReturn(container);
            when(container.getState()).thenReturn(state);
            when(state.getRunning()).thenReturn(true);
            when(container.getNetworkSettings()).thenReturn(settings);
            when(settings.getNetworks()).thenReturn(Map.of("extensions", network));
            when(network.getIpAddress()).thenReturn("127.0.0.1");
            var runtime = new DockerExtensionRuntime(docker, "extensions", server.getAddress().getPort(),
                    new io.artio.api.security.ExtensionCredentialService("unit-test-key-material-that-is-at-least-32-bytes"));

            var result = runtime.invoke("payments.example", 1, "payment.provider.v1", "authorize",
                    "00000000-0000-0000-0000-000000000099", "{}", "{}", Duration.ofSeconds(3));

            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(attempts).hasValue(2);
            assertThat(secondKey.get()).isEqualTo(firstKey.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void circuitBreakerOpensAfterFailuresAndAllowsOneRecoveryProbe() {
        long[] now = { 1_000L };
        var breaker = new ExtensionCircuitBreaker(2, Duration.ofSeconds(10), () -> now[0]);
        breaker.acquire();
        breaker.failed();
        breaker.acquire();
        breaker.failed();
        assertThatThrownBy(breaker::acquire).isInstanceOf(ExtensionCircuitBreaker.CircuitOpenException.class);

        now[0] += Duration.ofSeconds(10).toNanos();
        breaker.acquire();
        assertThatThrownBy(breaker::acquire).isInstanceOf(ExtensionCircuitBreaker.CircuitOpenException.class);
        breaker.succeeded();
        breaker.acquire();
    }
}
