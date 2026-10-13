package io.artio.api.extensions;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import io.artio.api.security.EncryptionService;
import io.artio.extensions.PluginManifest;
import io.artio.extensions.ExtensionRuntimePort;
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
public final class DockerExtensionRuntime implements ExtensionRuntimePort {
    private final int port;
    private static final long MAX_REQUEST_BYTES = 1_048_576;
    private static final long MAX_RESPONSE_BYTES = 1_048_576;
    private final DockerClient docker;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String network;
    private final EncryptionService encryption;
    private final java.util.concurrent.ConcurrentMap<String, ExtensionCircuitBreaker> circuits = new java.util.concurrent.ConcurrentHashMap<>();

    public DockerExtensionRuntime(DockerClient docker,
            @org.springframework.beans.factory.annotation.Value("${artio.extensions.docker.network:artio-extensions}") String network,
            @org.springframework.beans.factory.annotation.Value("${artio.extensions.docker.port:8080}") int port,
            EncryptionService encryption) {
        this.docker = docker;
        this.network = network;
        this.port = port;
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
            if (!Boolean.TRUE.equals(current.getState().getRunning())) docker.startContainerCmd(name).exec();
            awaitReady(manifest.id(), manifest.healthPath());
            return;
        } catch (NotFoundException ignored) {
            // Create only containers owned by this runtime; no mounts, published ports or caller-supplied options.
        }
        HostConfig host = restrictedHostConfig(network);
        docker.createContainerCmd(manifest.image()).withName(name).withHostConfig(host)
                .withUser("10001:10001").withExposedPorts(ExposedPort.tcp(port))
                .withEnv("ARTIO_EXTENSION_TOKEN=" + encryption.deriveExtensionCredential(manifest.id()), "ARTIO_EXTENSION_API_VERSION=1")
                .withLabels(Map.of("io.artio.extension", manifest.id(), "io.artio.managed", "true"))
                .exec();
        docker.startContainerCmd(name).exec();
        awaitReady(manifest.id(), manifest.healthPath());
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

    @Override
    public ExtensionRuntimePort.RuntimeHealth health(String pluginId, String healthPath) {
        try {
            InspectContainerResponse container = docker.inspectContainerCmd(containerName(pluginId)).exec();
            if (!Boolean.TRUE.equals(container.getState().getRunning())) return new ExtensionRuntimePort.RuntimeHealth(false, "STOPPED");
            var networks = container.getNetworkSettings().getNetworks();
            var networkInfo = networks == null ? null : networks.get(network);
            String address = networkInfo == null ? null : networkInfo.getIpAddress();
            if (address == null || address.isBlank()) return new ExtensionRuntimePort.RuntimeHealth(false, "NO_NETWORK_ADDRESS");
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + address + ":" + port + safeHealthPath(healthPath)))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return new ExtensionRuntimePort.RuntimeHealth(response.statusCode() >= 200 && response.statusCode() < 300,
                    "HTTP_" + response.statusCode());
        } catch (NotFoundException missing) {
            return new ExtensionRuntimePort.RuntimeHealth(false, "NOT_INSTALLED");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ExtensionRuntimePort.RuntimeHealth(false, "INTERRUPTED");
        } catch (Exception failure) {
            return new ExtensionRuntimePort.RuntimeHealth(false, "UNAVAILABLE");
        }
    }

    @Override
    public ExtensionRuntimePort.InvocationResult invoke(String pluginId, String extensionPoint, String operation, String idempotencyKey,
                                   String configurationJson, String requestJson, Duration deadline) {
        ExtensionRuntimePort.validateInvocation(extensionPoint, operation, idempotencyKey, configurationJson, requestJson, deadline);
        ExtensionCircuitBreaker breaker = circuits.computeIfAbsent(pluginId,
                ignored -> new ExtensionCircuitBreaker(5, Duration.ofSeconds(30)));
        try {
            breaker.acquire();
            var result = invokeOnce(pluginId, extensionPoint, operation, idempotencyKey, configurationJson, requestJson, deadline);
            breaker.succeeded();
            return result;
        } catch (ExtensionRuntimePort.ExtensionCallException providerFailure) {
            if (providerFailure.statusCode() >= 500) breaker.failed();
            else breaker.succeeded();
            throw providerFailure;
        } catch (ExtensionCircuitBreaker.CircuitOpenException open) {
            throw open;
        } catch (RuntimeException failure) {
            breaker.failed();
            throw failure;
        }
    }

    private ExtensionRuntimePort.InvocationResult invokeOnce(String pluginId, String extensionPoint, String operation,
            String idempotencyKey, String configurationJson, String requestJson, Duration deadline) {
        try {
            InspectContainerResponse container = docker.inspectContainerCmd(containerName(pluginId)).exec();
            if (!Boolean.TRUE.equals(container.getState().getRunning())) throw new ExtensionRuntimePort.ExtensionUnavailableException("Extension is stopped");
            var networkInfo = container.getNetworkSettings().getNetworks().get(network);
            String address = networkInfo == null ? null : networkInfo.getIpAddress();
            if (address == null || address.isBlank()) throw new ExtensionRuntimePort.ExtensionUnavailableException("Extension has no runtime network address");
            String envelope = "{\"protocolVersion\":1,\"extensionPoint\":\"" + jsonEscape(extensionPoint)
                    + "\",\"operation\":\"" + jsonEscape(operation) + "\",\"idempotencyKey\":\""
                    + jsonEscape(idempotencyKey) + "\",\"configuration\":" + configurationJson
                    + ",\"payload\":" + requestJson + "}";
            byte[] body = envelope.getBytes(StandardCharsets.UTF_8);
            if (body.length > MAX_REQUEST_BYTES) throw new IllegalArgumentException("Extension invocation exceeds 1 MiB");
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + address + ":" + port + "/v1/invoke"))
                    .timeout(deadline).header("Authorization", "Bearer " + encryption.deriveExtensionCredential(pluginId))
                    .header("Content-Type", "application/json").header("X-Artio-Extension-Api", "1")
                    .header("Idempotency-Key", idempotencyKey).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                byte[] responseBody = stream.readNBytes((int) MAX_RESPONSE_BYTES + 1);
                if (responseBody.length > MAX_RESPONSE_BYTES) throw new ExtensionRuntimePort.ExtensionProtocolException("Extension response exceeds 1 MiB");
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new ExtensionRuntimePort.ExtensionCallException(response.statusCode());
                }
                return new ExtensionRuntimePort.InvocationResult(response.statusCode(), new String(responseBody, StandardCharsets.UTF_8));
            }
        } catch (NotFoundException missing) {
            throw new ExtensionRuntimePort.ExtensionUnavailableException("Extension is not installed");
        } catch (java.net.http.HttpTimeoutException timeout) {
            throw new ExtensionRuntimePort.ExtensionTimeoutException("Extension invocation exceeded its deadline");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ExtensionRuntimePort.ExtensionUnavailableException("Extension invocation was interrupted");
        } catch (java.io.IOException failure) {
            throw new ExtensionRuntimePort.ExtensionUnavailableException("Extension could not be reached");
        }
    }

    private void awaitReady(String pluginId, String healthPath) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        do {
            if (health(pluginId, healthPath).healthy()) return;
            try {
                Thread.sleep(300);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                disable(pluginId);
                throw new ExtensionRuntimePort.ExtensionUnavailableException("Extension readiness was interrupted");
            }
        } while (System.nanoTime() < deadline);
        disable(pluginId);
        throw new ExtensionRuntimePort.ExtensionUnavailableException("Extension did not become healthy within 30 seconds");
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
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

}
