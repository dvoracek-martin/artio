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
    void persistentPluginVolumeNamesAreBoundToPluginServiceAndPathWithoutLeakingPath() {
        String first = DockerExtensionRuntime.volumeName("observability.artio", "prometheus", "/prometheus");
        assertThat(first).isEqualTo(DockerExtensionRuntime.volumeName("observability.artio", "prometheus", "/prometheus"));
        assertThat(first).isNotEqualTo(DockerExtensionRuntime.volumeName("observability.artio", "grafana", "/prometheus"))
                .isNotEqualTo(DockerExtensionRuntime.volumeName("observability.artio", "prometheus", "/var/lib/prometheus"));
        assertThat(first).startsWith("artio-vol-").doesNotContain("/prometheus");
    }

    @Test
    void observabilityProxyRejectsOtherPluginsMethodsAndTraversalBeforeDockerAccess() {
        var runtime = new DockerExtensionRuntime(null, "artio-extensions", 8080,
                new io.artio.api.security.ExtensionCredentialService("unit-test-key-material-that-is-at-least-32-bytes"));
        assertThatIllegalArgumentException().isThrownBy(() -> runtime.proxyObservability("payments.stripe", "GET", "/", null));
        assertThatIllegalArgumentException().isThrownBy(() -> runtime.proxyObservability("observability.artio", "POST", "/", null));
        assertThatIllegalArgumentException().isThrownBy(() -> runtime.proxyObservability("observability.artio", "GET", "/../admin", null));
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
        var largerSidecar = DockerExtensionRuntime.restrictedHostConfig("artio-extensions", 805_306_368L);
        assertThat(largerSidecar.getMemory()).isEqualTo(805_306_368L);
        assertThatIllegalArgumentException().isThrownBy(() -> DockerExtensionRuntime.restrictedHostConfig("artio-extensions", 3_000_000_000L));
    }

    @Test
    void resolvesOnlyDeclaredConfigurationBindingsIntoBoundedContainerEnvironment() {
        var secretField = new io.artio.extensions.PluginManifest.ConfigField("secret", true, true,
                "Password", "Plugin password", null, java.util.List.of(), null, null, null, null);
        var manifest = new io.artio.extensions.PluginManifest("1", "metrics.example", "Metrics", "Artio", "1.0.0",
                "Metrics extension", "0.1.0", "1.0.0", "ghcr.io/artio/metrics@sha256:" + "a".repeat(64),
                java.util.List.of(), java.util.List.of(), Map.of("password", secretField), java.util.List.of(),
                "/health", "/diagnostics");
        var credentials = new io.artio.api.security.ExtensionCredentialService("unit-test-key-material-that-is-at-least-32-bytes");

        var primary = DockerExtensionRuntime.resolvedEnvironment(manifest, true,
                Map.of("GF_SECURITY_ADMIN_PASSWORD", "password"), 1,
                Map.of("password", "a-private-password"), credentials);
        var sidecar = DockerExtensionRuntime.resolvedEnvironment(manifest, false,
                Map.of("GF_SECURITY_ADMIN_PASSWORD", "password"), 1,
                Map.of("password", "a-private-password"), credentials);

        assertThat(primary).contains("GF_SECURITY_ADMIN_PASSWORD=a-private-password",
                "ARTIO_EXTENSION_TOKEN=" + credentials.derive(manifest.id(), 1), "ARTIO_EXTENSION_API_VERSION=1",
                "HTTPS_PROXY=http://extension-egress:3128", "https_proxy=http://extension-egress:3128");
        assertThat(sidecar).contains("GF_SECURITY_ADMIN_PASSWORD=a-private-password",
                "HTTPS_PROXY=http://extension-egress:3128", "https_proxy=http://extension-egress:3128")
                .doesNotContain("ARTIO_EXTENSION_TOKEN=" + credentials.derive(manifest.id(), 1));
        assertThatThrownBy(() -> DockerExtensionRuntime.resolvedEnvironment(manifest, false,
                Map.of("GF_SECURITY_ADMIN_PASSWORD", "password"), 1, Map.of(), credentials))
                .isInstanceOf(IllegalStateException.class).hasMessage("Required plugin configuration is missing");
    }

    @Test
    void enablesBundleOnlyAfterEveryServicePassesItsHealthCheck() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var healthCalls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.createContext("/health", exchange -> { healthCalls.add("main"); exchange.sendResponseHeaders(204, -1); exchange.close(); });
        server.createContext("/metrics-health", exchange -> { healthCalls.add("metrics"); exchange.sendResponseHeaders(204, -1); exchange.close(); });
        server.createContext("/trace-health", exchange -> { healthCalls.add("trace"); exchange.sendResponseHeaders(204, -1); exchange.close(); });
        server.start();
        String pluginId = "observability.bundle";
        String mainName = DockerExtensionRuntime.containerName(pluginId);
        String serviceName = DockerExtensionRuntime.serviceName(pluginId, "metrics");
        try {
            DockerClient docker = mock(DockerClient.class);
            var mainCommand = mock(InspectContainerCmd.class);
            var serviceCommand = mock(InspectContainerCmd.class);
            var traceCommand = mock(InspectContainerCmd.class);
            var mainContainer = runningContainer(pluginId, "main", server.getAddress().getPort());
            var serviceContainer = runningContainer(pluginId, "metrics", server.getAddress().getPort());
            var traceContainer = runningContainer(pluginId, "trace", server.getAddress().getPort());
            when(docker.inspectContainerCmd(mainName)).thenReturn(mainCommand);
            when(docker.inspectContainerCmd(serviceName)).thenReturn(serviceCommand);
            when(docker.inspectContainerCmd(DockerExtensionRuntime.serviceName(pluginId, "trace"))).thenReturn(traceCommand);
            when(mainCommand.exec()).thenReturn(mainContainer);
            when(serviceCommand.exec()).thenReturn(serviceContainer);
            when(traceCommand.exec()).thenReturn(traceContainer);

            var manifest = new io.artio.extensions.PluginManifest("1", pluginId, "Observability", "Artio", "1.0.0", "Metrics bundle",
                    "0.1.0", "1.0.0", "ghcr.io/artio/observability@sha256:" + "a".repeat(64), java.util.List.of(),
                    java.util.List.of(), java.util.Map.of(), java.util.List.of(), "/health", "/diagnostics",
                    io.artio.extensions.PluginManifest.GeographicAvailability.global(),
                    java.util.List.of(new io.artio.extensions.PluginManifest.BundleService("metrics",
                                    "ghcr.io/artio/metrics@sha256:" + "b".repeat(64), server.getAddress().getPort(), "/metrics-health", Map.of(), java.util.List.of("trace")),
                            new io.artio.extensions.PluginManifest.BundleService("trace",
                                    "ghcr.io/artio/trace@sha256:" + "c".repeat(64), server.getAddress().getPort(), "/trace-health")));
            var runtime = new DockerExtensionRuntime(docker, "extensions", server.getAddress().getPort(),
                    new io.artio.api.security.ExtensionCredentialService("unit-test-key-material-that-is-at-least-32-bytes"));

            runtime.enable(manifest, 1);
            var health = runtime.health(manifest);

            assertThat(healthCalls).containsExactly("main", "trace", "metrics", "main", "metrics", "trace");
            assertThat(health.healthy()).isTrue();
            assertThat(health.status()).isEqualTo("HEALTHY");
            assertThat(health.services()).containsEntry("main", "HTTP_204")
                    .containsEntry("metrics", "HTTP_204").containsEntry("trace", "HTTP_204");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void forwardsOnlyBoundedOtlpSignalsToManagedObservabilityCollector() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var received = new AtomicReference<String>();
        server.createContext("/v1/metrics", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String pluginId = "observability.artio";
            String collectorName = DockerExtensionRuntime.serviceName(pluginId, "collector");
            DockerClient docker = mock(DockerClient.class);
            var command = mock(InspectContainerCmd.class);
            var container = runningContainer(pluginId, "collector", 13133, 4318);
            var networkSettings = container.getNetworkSettings();
            var bindings = new com.github.dockerjava.api.model.Ports();
            bindings.bind(com.github.dockerjava.api.model.ExposedPort.tcp(4318),
                    com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1", server.getAddress().getPort()));
            when(networkSettings.getPorts()).thenReturn(bindings);
            when(docker.inspectContainerCmd(collectorName)).thenReturn(command);
            when(command.exec()).thenReturn(container);
            var runtime = new DockerExtensionRuntime(docker, "extensions", 8080,
                    new io.artio.api.security.ExtensionCredentialService("unit-test-key-material-that-is-at-least-32-bytes"), true);

            var response = runtime.forwardOtlp(pluginId, "metrics", "test-otlp".getBytes(StandardCharsets.UTF_8));

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(received.get()).isEqualTo("test-otlp");
        } finally {
            server.stop(0);
        }
    }

    private static InspectContainerResponse runningContainer(String pluginId, String serviceId, int port) {
        return runningContainer(pluginId, serviceId, port, new int[0]);
    }

    private static InspectContainerResponse runningContainer(String pluginId, String serviceId, int port, int... additionalPorts) {
        var container = mock(InspectContainerResponse.class);
        var state = mock(InspectContainerResponse.ContainerState.class);
        var config = mock(com.github.dockerjava.api.model.ContainerConfig.class);
        var settings = mock(NetworkSettings.class);
        var network = mock(ContainerNetwork.class);
        when(container.getId()).thenReturn("container-" + serviceId);
        when(container.getState()).thenReturn(state);
        when(state.getRunning()).thenReturn(true);
        when(container.getConfig()).thenReturn(config);
        when(config.getLabels()).thenReturn(Map.of("io.artio.extension", pluginId, "io.artio.managed", "true",
                "io.artio.extension.service", serviceId, "io.artio.extension.ports", java.util.Arrays.stream(additionalPorts)
                        .mapToObj(Integer::toString).collect(java.util.stream.Collectors.joining(","))));
        var exposedPorts = new java.util.ArrayList<com.github.dockerjava.api.model.ExposedPort>();
        exposedPorts.add(com.github.dockerjava.api.model.ExposedPort.tcp(port));
        java.util.Arrays.stream(additionalPorts).forEach(additional -> exposedPorts.add(com.github.dockerjava.api.model.ExposedPort.tcp(additional)));
        when(config.getExposedPorts()).thenReturn(exposedPorts.toArray(com.github.dockerjava.api.model.ExposedPort[]::new));
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
