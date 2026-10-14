package io.artio.api.extensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.extensions.ExtensionRuntimePort;
import io.artio.extensions.PluginManifest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/** API-side client for the isolated Docker lifecycle controller; it never opens a Docker socket. */
@Component
@ConditionalOnExpression("'${artio.extensions.docker.enabled:false}' == 'true' and '${artio.extensions.docker.mode:remote}' == 'remote'")
public final class RemoteExtensionRuntime implements ExtensionRuntimePort {
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final URI baseUri;
    private final String token;

    public RemoteExtensionRuntime(ObjectMapper mapper,
            @Value("${artio.extensions.controller.url:http://extension-controller:8090}") String baseUrl,
            @Value("${artio.extensions.controller.token:}") String token) {
        if (token.length() < 32) throw new IllegalStateException("Extension controller token must contain at least 32 characters");
        this.mapper = mapper;
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        if (!"http".equals(baseUri.getScheme()) || baseUri.getHost() == null || baseUri.getUserInfo() != null) {
            throw new IllegalStateException("Extension controller URL must be an internal HTTP endpoint");
        }
        this.token = token;
    }

    @Override public void install(PluginManifest manifest) { post("internal/v1/extensions/install", new ManifestRequest(manifest, 1), Duration.ofSeconds(150), Void.class); }
    @Override public void update(PluginManifest manifest) { post("internal/v1/extensions/update", new ManifestRequest(manifest, 1), Duration.ofSeconds(150), Void.class); }
    @Override public void enable(PluginManifest manifest) { enable(manifest, 1); }
    @Override public void enable(PluginManifest manifest, int generation) {
        enable(manifest, generation, Map.of());
    }
    @Override public void enable(PluginManifest manifest, int generation, Map<String, String> configuration) {
        post("internal/v1/extensions/enable", new EnableRequest(manifest, generation, configuration), Duration.ofSeconds(45), Void.class);
    }
    @Override public void disable(String pluginId) {
        post("internal/v1/extensions/" + segment(pluginId) + "/disable", null, Duration.ofSeconds(30), Void.class);
    }
    @Override public void uninstall(String pluginId) {
        post("internal/v1/extensions/" + segment(pluginId) + "/uninstall", null, Duration.ofSeconds(30), Void.class);
    }
    @Override public void rotateCredentials(PluginManifest manifest, int generation, boolean restartIfEnabled) {
        rotateCredentials(manifest, generation, restartIfEnabled, Map.of());
    }
    @Override public void rotateCredentials(PluginManifest manifest, int generation, boolean restartIfEnabled,
            Map<String, String> configuration) {
        post("internal/v1/extensions/rotate", new RotationRequest(manifest, generation, restartIfEnabled, configuration), Duration.ofSeconds(45), Void.class);
    }
    @Override public RuntimeHealth health(String pluginId, String healthPath) {
        return post("internal/v1/extensions/" + segment(pluginId) + "/health", new HealthRequest(healthPath), Duration.ofSeconds(10), RuntimeHealth.class);
    }
    @Override public RuntimeHealth health(PluginManifest manifest) {
        return post("internal/v1/extensions/" + segment(manifest.id()) + "/bundle-health", new ManifestHealthRequest(manifest), Duration.ofSeconds(30), RuntimeHealth.class);
    }
    @Override public UiProxyResponse proxyObservability(String pluginId, String method, String path, String query) {
        if (!"observability.artio".equals(pluginId)) throw new IllegalArgumentException("Unsupported observability plugin");
        GrafanaProxyResponse response = post("internal/v1/observability/grafana",
                new GrafanaProxyRequest(method, path, query), Duration.ofSeconds(15), GrafanaProxyResponse.class);
        if (response.bodyBase64() == null || response.bodyBase64().length() > 11_184_812) {
            throw new ExtensionProtocolException("Observability response exceeded its size limit");
        }
        try {
            byte[] body = java.util.Base64.getDecoder().decode(response.bodyBase64());
            if (body.length > 8_388_608) throw new ExtensionProtocolException("Observability response exceeded its size limit");
            return new UiProxyResponse(response.statusCode(), response.contentType(), response.headers(), body);
        } catch (IllegalArgumentException invalid) {
            throw new ExtensionProtocolException("Observability response was invalid");
        }
    }
    @Override public InvocationResult invoke(String pluginId, String extensionPoint, String operation, String idempotencyKey,
            String configurationJson, String requestJson, Duration deadline) {
        return invoke(pluginId, 1, extensionPoint, operation, idempotencyKey, configurationJson, requestJson, deadline);
    }
    @Override public InvocationResult invoke(String pluginId, int generation, String extensionPoint, String operation,
            String idempotencyKey, String configurationJson, String requestJson, Duration deadline) {
        ExtensionRuntimePort.validateInvocation(extensionPoint, operation, idempotencyKey, configurationJson, requestJson, deadline);
        var request = new InvocationRequest(pluginId, generation, extensionPoint, operation, idempotencyKey,
                configurationJson, requestJson, deadline.toMillis());
        return post("internal/v1/extensions/invoke", request, deadline.plusSeconds(5), InvocationResult.class);
    }

    private <T> T post(String path, Object payload, Duration timeout, Class<T> responseType) {
        try {
            var builder = HttpRequest.newBuilder(baseUri.resolve(path)).timeout(timeout)
                    .header("Authorization", "Bearer " + token).header("Accept", "application/json");
            if (payload == null) builder.POST(HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload), StandardCharsets.UTF_8));
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw translate(response);
            if (responseType == Void.class || response.body().isBlank()) return null;
            return mapper.readValue(response.body(), responseType);
        } catch (ExtensionRuntimePort.ExtensionUnavailableException | ExtensionRuntimePort.ExtensionTimeoutException
                | ExtensionRuntimePort.ExtensionCallException | IllegalArgumentException | IllegalStateException failure) {
            throw failure;
        } catch (java.net.http.HttpTimeoutException timeoutFailure) {
            throw new ExtensionTimeoutException("Extension controller request timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ExtensionUnavailableException("Extension controller request was interrupted");
        } catch (Exception failure) {
            throw new ExtensionUnavailableException("Extension controller could not be reached");
        }
    }

    private static RuntimeException translate(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 502) {
            try {
                int providerStatus = Integer.parseInt(response.headers().firstValue("X-Artio-Extension-Status").orElse("502"));
                return new ExtensionCallException(providerStatus);
            } catch (NumberFormatException ignored) { return new ExtensionUnavailableException("Extension controller returned an invalid error response"); }
        }
        if (status == 504) return new ExtensionTimeoutException("Extension invocation timed out");
        if (status == 503) return new ExtensionUnavailableException("Extension runtime is unavailable");
        if (status == 400) return new IllegalArgumentException("Extension runtime rejected an invalid request");
        if (status == 409) return new IllegalStateException("Extension runtime rejected the lifecycle transition");
        return new ExtensionUnavailableException("Extension controller returned HTTP " + status);
    }

    private static String segment(String pluginId) {
        if (pluginId == null || !pluginId.matches("[a-z0-9][a-z0-9._-]{0,159}")) throw new IllegalArgumentException("Invalid plugin id");
        return pluginId;
    }

    private record ManifestRequest(PluginManifest manifest, int credentialGeneration) { }
    private record EnableRequest(PluginManifest manifest, int credentialGeneration, Map<String, String> configuration) {
        private EnableRequest { configuration = configuration == null ? Map.of() : Map.copyOf(configuration); }
    }
    private record RotationRequest(PluginManifest manifest, int credentialGeneration, boolean restartIfEnabled,
                                   Map<String, String> configuration) {
        private RotationRequest { configuration = configuration == null ? Map.of() : Map.copyOf(configuration); }
    }
    private record HealthRequest(String healthPath) { }
    private record ManifestHealthRequest(PluginManifest manifest) { }
    private record GrafanaProxyRequest(String method, String path, String query) { }
    private record GrafanaProxyResponse(int statusCode, String contentType, Map<String, String> headers, String bodyBase64) { }
    private record InvocationRequest(String pluginId, int credentialGeneration, String extensionPoint, String operation,
            String idempotencyKey, String configurationJson, String requestJson, long deadlineMillis) { }
}
