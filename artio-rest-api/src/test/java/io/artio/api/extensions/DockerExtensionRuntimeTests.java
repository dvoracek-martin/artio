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
                "00000000-0000-0000-0000-000000000001", "x".repeat(1_048_576), Duration.ofSeconds(1));
        assertThatIllegalArgumentException().isThrownBy(() -> io.artio.extensions.ExtensionRuntimePort.validateInvocation(
                "payment.provider.v1", "authorize", "00000000-0000-0000-0000-000000000001", "x".repeat(1_048_577), Duration.ofSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> io.artio.extensions.ExtensionRuntimePort.validateInvocation(
                "payment.provider.v1", "authorize", "00000000-0000-0000-0000-000000000001", "", Duration.ofSeconds(1)));
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
    void invocationEnvelopeHasStrictBoundsAndStableRequestIdentity() {
        io.artio.extensions.ExtensionRuntimePort.validateInvocation("payment.provider.v1", "authorize", "00000000-0000-0000-0000-000000000001",
                "{\"amount\":100}", java.time.Duration.ofSeconds(5));
        assertThatIllegalArgumentException().isThrownBy(() -> io.artio.extensions.ExtensionRuntimePort.validateInvocation("payment.provider.v1",
                "authorize", "invalid", "{}", java.time.Duration.ofSeconds(5)));
        assertThatIllegalArgumentException().isThrownBy(() -> io.artio.extensions.ExtensionRuntimePort.validateInvocation("payment.provider.v1",
                "authorize", "00000000-0000-0000-0000-000000000001", "{}", java.time.Duration.ofSeconds(31)));
        assertThatIllegalArgumentException().isThrownBy(() -> io.artio.extensions.ExtensionRuntimePort.validateInvocation("../../host",
                "authorize", "00000000-0000-0000-0000-000000000001", "{}", java.time.Duration.ofSeconds(5)));
    }

    @Test
    void extensionCredentialsAreStableAndScopedToThePlugin() {
        var encryption = new io.artio.api.security.EncryptionService("unit-test-key-material-that-is-at-least-32-bytes");
        String token = encryption.deriveExtensionCredential("payments.example");
        assertThat(token).isEqualTo(encryption.deriveExtensionCredential("payments.example"));
        assertThat(token).isNotEqualTo(encryption.deriveExtensionCredential("shipping.example"));
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

            var runtime = new DockerExtensionRuntime(docker, "extensions", server.getAddress().getPort(), encryption);
            String key = "00000000-0000-0000-0000-000000000001";
            var result = runtime.invoke("payments.example", "payment.provider.v1", "authorize", key,
                    "{\"amount\":125}", Duration.ofSeconds(2));

            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(new ObjectMapper().readTree(result.responseJson()).get("authorized").asBoolean()).isTrue();
            assertThat(authorization.get()).isEqualTo("Bearer " + encryption.deriveExtensionCredential("payments.example"));
            assertThat(apiVersion.get()).isEqualTo("1");
            assertThat(idempotencyKey.get()).isEqualTo(key);
            JsonNode envelope = new ObjectMapper().readTree(body.get());
            assertThat(envelope.get("protocolVersion").asInt()).isEqualTo(1);
            assertThat(envelope.get("extensionPoint").asText()).isEqualTo("payment.provider.v1");
            assertThat(envelope.get("operation").asText()).isEqualTo("authorize");
            assertThat(envelope.get("idempotencyKey").asText()).isEqualTo(key);
            assertThat(envelope.get("payload").get("amount").asInt()).isEqualTo(125);
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
