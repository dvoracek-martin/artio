package io.artio.api.extensions;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import io.artio.api.security.EncryptionService;
import io.artio.extensions.PluginManifest;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Restricted Docker runtime for digest-pinned extensions. The Docker daemon is an administrative trust boundary. */
@Component
@ConditionalOnProperty(prefix = "artio.extensions.docker", name = "enabled", havingValue = "true")
public final class DockerExtensionRuntime {
    private static final int PORT = 8080;
    private static final long MAX_REQUEST_BYTES = 1_048_576;
    private static final long MAX_RESPONSE_BYTES = 1_048_576;
    private final DockerClient docker;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String network;
    private final EncryptionService encryption;

    public DockerExtensionRuntime(DockerClient docker,
            @org.springframework.beans.factory.annotation.Value("${artio.extensions.docker.network:artio-extensions}") String network,
            EncryptionService encryption) {
        this.docker = docker;
        this.network = network;
        this.encryption = encryption;
    }

    public void install(PluginManifest manifest) {
        requirePinnedImage(manifest.image());
        try {
            docker.pullImageCmd(manifest.image()).start().awaitCompletion(120, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Extension image pull was interrupted", interrupted);
        }
    }

    public void enable(PluginManifest manifest) {
        requirePinnedImage(manifest.image());
        String name = containerName(manifest.id());
        try {
            InspectContainerResponse current = docker.inspectContainerCmd(name).exec();
            if (Boolean.TRUE.equals(current.getState().getRunning())) return;
            docker.startContainerCmd(name).exec();
            return;
        } catch (NotFoundException ignored) {
            // Create only containers owned by this runtime; no mounts, published ports or caller-supplied options.
        }
        HostConfig host = restrictedHostConfig(network);
        docker.createContainerCmd(manifest.image()).withName(name).withHostConfig(host)
                .withUser("10001:10001").withExposedPorts(ExposedPort.tcp(PORT))
                .withEnv("ARTIO_EXTENSION_TOKEN=" + encryption.deriveExtensionCredential(manifest.id()), "ARTIO_EXTENSION_API_VERSION=1")
                .withLabels(Map.of("io.artio.extension", manifest.id(), "io.artio.managed", "true"))
                .exec();
        docker.startContainerCmd(name).exec();
    }

    static HostConfig restrictedHostConfig(String network) {
        return HostConfig.newHostConfig().withNetworkMode(network).withMemory(268_435_456L)
                .withNanoCPUs(1_000_000_000L).withPidsLimit(128L).withReadonlyRootfs(true)
                .withCapDrop(Capability.ALL).withSecurityOpts(java.util.List.of("no-new-privileges:true"))
                .withTmpFs(Map.of("/tmp", "rw,noexec,nosuid,size=16m"));
    }

    public void disable(String pluginId) {
        String name = containerName(pluginId);
        try {
            InspectContainerResponse current = docker.inspectContainerCmd(name).exec();
            if (Boolean.TRUE.equals(current.getState().getRunning())) docker.stopContainerCmd(name).withTimeout(10).exec();
        } catch (NotFoundException ignored) {
            // Already stopped/removed.
        }
    }

    public RuntimeHealth health(String pluginId, String healthPath) {
        try {
            InspectContainerResponse container = docker.inspectContainerCmd(containerName(pluginId)).exec();
            if (!Boolean.TRUE.equals(container.getState().getRunning())) return new RuntimeHealth(false, "STOPPED");
            var networks = container.getNetworkSettings().getNetworks();
            var networkInfo = networks == null ? null : networks.get(network);
            String address = networkInfo == null ? null : networkInfo.getIpAddress();
            if (address == null || address.isBlank()) return new RuntimeHealth(false, "NO_NETWORK_ADDRESS");
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + address + ":" + PORT + safeHealthPath(healthPath)))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return new RuntimeHealth(response.statusCode() >= 200 && response.statusCode() < 300,
                    "HTTP_" + response.statusCode());
        } catch (NotFoundException missing) {
            return new RuntimeHealth(false, "NOT_INSTALLED");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new RuntimeHealth(false, "INTERRUPTED");
        } catch (Exception failure) {
            return new RuntimeHealth(false, "UNAVAILABLE");
        }
    }

    public InvocationResult invoke(String pluginId, String extensionPoint, String operation, String idempotencyKey,
                                   String requestJson, Duration deadline) {
        validateInvocation(extensionPoint, operation, idempotencyKey, requestJson, deadline);
        try {
            InspectContainerResponse container = docker.inspectContainerCmd(containerName(pluginId)).exec();
            if (!Boolean.TRUE.equals(container.getState().getRunning())) throw new ExtensionUnavailableException("Extension is stopped");
            var networkInfo = container.getNetworkSettings().getNetworks().get(network);
            String address = networkInfo == null ? null : networkInfo.getIpAddress();
            if (address == null || address.isBlank()) throw new ExtensionUnavailableException("Extension has no runtime network address");
            byte[] payload = requestJson.getBytes(StandardCharsets.UTF_8);
            String envelope = "{\"protocolVersion\":1,\"extensionPoint\":\"" + jsonEscape(extensionPoint)
                    + "\",\"operation\":\"" + jsonEscape(operation) + "\",\"idempotencyKey\":\""
                    + jsonEscape(idempotencyKey) + "\",\"payload\":" + requestJson + "}";
            byte[] body = envelope.getBytes(StandardCharsets.UTF_8);
            if (body.length > MAX_REQUEST_BYTES) throw new IllegalArgumentException("Extension invocation exceeds 1 MiB");
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + address + ":" + PORT + "/v1/invoke"))
                    .timeout(deadline).header("Authorization", "Bearer " + encryption.deriveExtensionCredential(pluginId))
                    .header("Content-Type", "application/json").header("X-Artio-Extension-Api", "1")
                    .header("Idempotency-Key", idempotencyKey).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                byte[] responseBody = stream.readNBytes((int) MAX_RESPONSE_BYTES + 1);
                if (responseBody.length > MAX_RESPONSE_BYTES) throw new ExtensionProtocolException("Extension response exceeds 1 MiB");
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new ExtensionCallException(response.statusCode());
                }
                return new InvocationResult(response.statusCode(), new String(responseBody, StandardCharsets.UTF_8));
            }
        } catch (NotFoundException missing) {
            throw new ExtensionUnavailableException("Extension is not installed");
        } catch (java.net.http.HttpTimeoutException timeout) {
            throw new ExtensionTimeoutException("Extension invocation exceeded its deadline");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ExtensionUnavailableException("Extension invocation was interrupted");
        } catch (java.io.IOException failure) {
            throw new ExtensionUnavailableException("Extension could not be reached");
        }
    }

    static void validateInvocation(String extensionPoint, String operation, String idempotencyKey,
                                           String requestJson, Duration deadline) {
        if (extensionPoint == null || !extensionPoint.matches("[a-z][a-z0-9.-]{1,120}\\.v[1-9][0-9]*"))
            throw new IllegalArgumentException("Extension point must be a versioned identifier");
        if (operation == null || !operation.matches("[a-z][a-zA-Z0-9_.-]{0,63}"))
            throw new IllegalArgumentException("Invalid extension operation");
        try { java.util.UUID.fromString(idempotencyKey); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Idempotency key must be a UUID", invalid); }
        validateInvocationSize(requestJson == null ? null : requestJson.getBytes(StandardCharsets.UTF_8));
        if (deadline == null || deadline.compareTo(Duration.ofMillis(100)) < 0 || deadline.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("Extension deadline must be between 100 ms and 30 s");
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Checks request framing limits for the invocation adapter; transport is intentionally a separate API contract. */
    public static void validateInvocationSize(byte[] request) {
        if (request == null || request.length == 0 || request.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Extension invocation must be between 1 byte and 1 MiB");
        }
    }

    static String containerName(String pluginId) {
        if (pluginId == null || pluginId.length() > 160 || !pluginId.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("Invalid plugin id for Docker runtime");
        }
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(pluginId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String suffix = java.util.HexFormat.of().formatHex(hash, 0, 6);
            String slug = pluginId.replace('.', '-');
            if (slug.length() > 42) slug = slug.substring(0, 42);
            return "artio-ext-" + slug + "-" + suffix;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String safeHealthPath(String path) {
        if (path == null || !path.matches("/[A-Za-z0-9/_-]{1,120}") || path.contains("..")) {
            throw new IllegalArgumentException("Plugin health path is invalid");
        }
        return path;
    }

    private static void requirePinnedImage(String image) {
        if (image == null || !image.matches("[a-zA-Z0-9][a-zA-Z0-9./:_-]*@sha256:[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("Extension runtime requires an immutable sha256 image digest");
        }
    }

    public record RuntimeHealth(boolean healthy, String status) { }
    public record InvocationResult(int statusCode, String responseJson) { }
    public static class ExtensionUnavailableException extends RuntimeException { public ExtensionUnavailableException(String message) { super(message); } }
    public static class ExtensionTimeoutException extends RuntimeException { public ExtensionTimeoutException(String message) { super(message); } }
    public static class ExtensionProtocolException extends RuntimeException { public ExtensionProtocolException(String message) { super(message); } }
    public static class ExtensionCallException extends RuntimeException {
        private final int statusCode;
        ExtensionCallException(int statusCode) { super("Extension returned an unsuccessful status"); this.statusCode = statusCode; }
        public int statusCode() { return statusCode; }
    }
}
