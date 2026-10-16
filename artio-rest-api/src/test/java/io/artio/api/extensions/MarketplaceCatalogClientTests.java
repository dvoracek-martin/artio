package io.artio.api.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class MarketplaceCatalogClientTests {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void searchesConfiguredPublicCatalogWithCoreAndCountryFilters() throws Exception {
        AtomicReference<String> requestUri = new AtomicReference<>();
        server = server(exchange -> {
            requestUri.set(exchange.getRequestURI().toString());
            respond(exchange, 200, "{\"content\":[{\"pluginId\":\"shipping.demo\"}],\"totalElements\":1}");
        });

        var page = client().plugins("Swiss Post", null, "CH", "1.4.2", false, "de", 0, 20);

        assertThat(page.path("content").get(0).path("pluginId").asText()).isEqualTo("shipping.demo");
        assertThat(requestUri.get()).contains("coreVersion=1.4.2", "country=CH", "q=Swiss+Post", "locale=de");
        assertThat(requestUri.get()).startsWith("/api/v1/marketplace/plugins?");
    }

    @Test
    void retrievesOriginalManifestOnlyForSelectedMarketplaceRelease() throws Exception {
        AtomicReference<String> requestUri = new AtomicReference<>();
        server = server(exchange -> {
            requestUri.set(exchange.getRequestURI().toString());
            respond(exchange, 200, "{\"id\":\"shipping.demo\",\"version\":\"1.2.3\",\"image\":\"localhost:15000/public/plugins/shipping-demo@sha256:abc\"}");
        });

        var manifest = client().releaseManifest("shipping.demo", "1.2.3", "1.4.2", "CH", false);

        assertThat(manifest.path("id").asText()).isEqualTo("shipping.demo");
        assertThat(requestUri.get()).startsWith("/api/v1/marketplace/plugins/shipping.demo/releases/1.2.3/manifest?");
        assertThat(requestUri.get()).contains("coreVersion=1.4.2", "country=CH");
    }

    @Test
    void refusesUnconfiguredMarketplaceAndOversizedResponses() throws Exception {
        assertThatThrownBy(() -> new MarketplaceCatalogClient(new ObjectMapper(), "").plugins(null, null, null,
                "1.0.0", false, "en", 0, 20))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Marketplace is not configured");
        server = server(exchange -> respond(exchange, 200, "x".repeat(2 * 1024 * 1024 + 1)));
        assertThatThrownBy(() -> client().plugins(null, null, null, "1.0.0", false, "en", 0, 20))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("size limit");
    }

    private MarketplaceCatalogClient client() {
        return new MarketplaceCatalogClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private HttpServer server(HttpHandler handler) throws Exception {
        HttpServer result = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        result.createContext("/", handler);
        result.start();
        return result;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
