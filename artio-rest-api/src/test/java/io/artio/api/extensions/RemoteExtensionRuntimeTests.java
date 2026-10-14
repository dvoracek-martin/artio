package io.artio.api.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import io.artio.extensions.ExtensionRuntimePort;

class RemoteExtensionRuntimeTests {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsAuthenticatedHealthRequestToTheInternalController() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/extensions/test-plugin/health", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"healthy\":true,\"status\":\"HTTP_200\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        var runtime = new RemoteExtensionRuntime(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(),
                "integration-controller-token-value-at-least-32-characters");
        var health = runtime.health("test-plugin", "/health");

        assertThat(authorization.get()).isEqualTo("Bearer integration-controller-token-value-at-least-32-characters");
        assertThat(health.healthy()).isTrue();
        assertThat(health.status()).isEqualTo("HTTP_200");
    }

    @Test
    void mapsControllerProviderFailuresBackToTheExtensionContract() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/extensions/invoke", exchange -> {
            exchange.getResponseHeaders().set("X-Artio-Extension-Status", "422");
            byte[] body = "{\"code\":\"EXTENSION_CALL_FAILED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(502, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var runtime = new RemoteExtensionRuntime(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(),
                "integration-controller-token-value-at-least-32-characters");

        assertThatThrownBy(() -> runtime.invoke("test-plugin", 1, "payment.authorization.v1", "authorize",
                java.util.UUID.randomUUID().toString(), "{}", "{}", Duration.ofSeconds(2)))
                .isInstanceOf(ExtensionRuntimePort.ExtensionCallException.class)
                .extracting(failure -> ((ExtensionRuntimePort.ExtensionCallException) failure).statusCode())
                .isEqualTo(422);
    }
}
