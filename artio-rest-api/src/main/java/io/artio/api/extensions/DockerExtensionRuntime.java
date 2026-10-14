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
    private final boolean hostLoopbackTestAccess;
    private final java.util.concurrent.ConcurrentMap<String, ExtensionCircuitBreaker> circuits = new java.util.concurrent.ConcurrentHashMap<>();

    public DockerExtensionRuntime(DockerClient docker,
            @org.springframework.beans.factory.annotation.Value("${artio.extensions.docker.network:artio-extensions}") String network,
            @org.springframework.beans.factory.annotation.Value("${artio.extensions.docker.port:8080}") int port,
            EncryptionService encryption) {
        this(docker, network, port, encryption, false);
    }

    DockerExtensionRuntime(DockerClient docker, String network, int port, EncryptionService encryption,
                           boolean hostLoopbackTestAccess) {
        this.docker = docker;
        this.network = network;
        this.port = port;
        this.encryption = encryption;
        this.hostLoopbackTestAccess = hostLoopbackTestAccess;
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

    @Override
    public void update(PluginManifest manifest) {
        requirePinnedImage(manifest.image());
        install(manifest); // Pull first: a failed download leaves the current stopped runtime intact.
        removeStoppedManagedContainer(manifest.id());
    }

    public void enable(PluginManifest manifest) {
        enable(manifest, 1);
    }

    @Override
    public void enable(PluginManifest manifest, int credentialGeneration) {
        if (credentialGeneration < 1) throw new IllegalArgumentException("Credential generation must be positive");
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
        if (hostLoopbackTestAccess) {
            var bindings = new com.github.dockerjava.api.model.Ports();
            bindings.bind(ExposedPort.tcp(port), com.github.dockerjava.api.model.Ports.Binding.bindIp("127.0.0.1"));
            host.withPortBindings(bindings);
        }
        docker.createContainerCmd(manifest.image()).withName(name).withHostConfig(host)
                .withUser("10001:10001").withExposedPorts(ExposedPort.tcp(port))
                .withEnv("ARTIO_EXTENSION_TOKEN=" + encryption.deriveExtensionCredential(manifest.id(), credentialGeneration), "ARTIO_EXTENSION_API_VERSION=1")
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
    public void uninstall(String pluginId) {
        String name = containerName(pluginId);
        try {
            InspectContainerResponse current = docker.inspectContainerCmd(name).exec();
            if (current.getConfig() == null || current.getConfig().getLabels() == null
                    || !"true".equals(current.getConfig().getLabels().get("io.artio.managed"))
                    || !pluginId.equals(current.getConfig().getLabels().get("io.artio.extension"))) {
                throw new IllegalStateException("Refusing to remove an unmanaged extension container");
            }
            if (Boolean.TRUE.equals(current.getState().getRunning())) {
                throw new IllegalStateException("Disable the extension before uninstalling it");
            }
            docker.removeContainerCmd(name).withForce(false).exec();
        } catch (NotFoundException ignored) {
            // A registered but never installed extension has no runtime container.
        }
    }

    @Override
    public void rotateCredentials(PluginManifest manifest, int credentialGeneration, boolean restartIfEnabled) {
        String name = containerName(manifest.id());
        try {
            InspectContainerResponse current = docker.inspectContainerCmd(name).exec();
            if (current.getConfig() == null || current.getConfig().getLabels() == null
                    || !"true".equals(current.getConfig().getLabels().get("io.artio.managed"))
                    || !manifest.id().equals(current.getConfig().getLabels().get("io.artio.extension"))) {
                throw new IllegalStateException("Refusing to replace an unmanaged extension container");
            }
            if (Boolean.TRUE.equals(current.getState().getRunning())) docker.stopContainerCmd(name).withTimeout(10).exec();
            docker.removeContainerCmd(name).withForce(true).exec();
        } catch (NotFoundException ignored) {
            // A stopped or not-yet-created extension has no old runtime credential to revoke.
        }
        if (restartIfEnabled) enable(manifest, credentialGeneration);
    }

    private void removeStoppedManagedContainer(String pluginId) {
        String name = containerName(pluginId);
        try {
            InspectContainerResponse current = docker.inspectContainerCmd(name).exec();
            if (current.getConfig() == null || current.getConfig().getLabels() == null
                    || !"true".equals(current.getConfig().getLabels().get("io.artio.managed"))
                    || !pluginId.equals(current.getConfig().getLabels().get("io.artio.extension"))) {
                throw new IllegalStateException("Refusing to replace an unmanaged extension container");
            }
            if (Boolean.TRUE.equals(current.getState().getRunning())) {
                throw new IllegalStateException("Disable the extension before updating its image");
            }
            docker.removeContainerCmd(name).withForce(false).exec();
        } catch (NotFoundException ignored) {
            // Install-only plugins have no container to replace.
        }
    }

    @Override
    public ExtensionRuntimePort.RuntimeHealth health(String pluginId, String healthPath) {
        try {
            InspectContainerResponse container = docker.inspectContainerCmd(containerName(pluginId)).exec();
            if (!Boolean.TRUE.equals(container.getState().getRunning())) return new ExtensionRuntimePort.RuntimeHealth(false, "STOPPED");
            URI endpoint = endpoint(container, safeHealthPath(healthPath));
            if (endpoint == null) return new ExtensionRuntimePort.RuntimeHealth(false, "NO_NETWORK_ADDRESS");
            HttpRequest request = HttpRequest.newBuilder(endpoint)
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
        return invoke(pluginId, 1, extensionPoint, operation, idempotencyKey, configurationJson, requestJson, deadline);
    }

    @Override
    public ExtensionRuntimePort.InvocationResult invoke(String pluginId, int credentialGeneration, String extensionPoint,
            String operation, String idempotencyKey, String configurationJson, String requestJson, Duration deadline) {
        if (credentialGeneration < 1) throw new IllegalArgumentException("Credential generation must be positive");
        ExtensionRuntimePort.validateInvocation(extensionPoint, operation, idempotencyKey, configurationJson, requestJson, deadline);
        ExtensionCircuitBreaker breaker = circuits.computeIfAbsent(pluginId,
                ignored -> new ExtensionCircuitBreaker(5, Duration.ofSeconds(30)));
        try {
            breaker.acquire();
            long deadlineNanos = System.nanoTime() + deadline.toNanos();
            ExtensionRuntimePort.InvocationResult result;
            for (int attempt = 0; ; attempt++) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) throw new ExtensionRuntimePort.ExtensionTimeoutException("Extension invocation exceeded its deadline");
                try {
                    result = invokeOnce(pluginId, credentialGeneration, extensionPoint, operation, idempotencyKey, configurationJson,
                            requestJson, Duration.ofNanos(remainingNanos));
                    break;
                } catch (ExtensionRuntimePort.ExtensionCallException failure) {
                    if (attempt >= 1 || !isRetryable(failure.statusCode())) throw failure;
                    long pauseNanos = Math.min(Duration.ofMillis(50).toNanos(), deadlineNanos - System.nanoTime());
                    if (pauseNanos <= 0) throw new ExtensionRuntimePort.ExtensionTimeoutException("Extension invocation exceeded its deadline");
                    try {
                        TimeUnit.NANOSECONDS.sleep(pauseNanos);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new ExtensionRuntimePort.ExtensionUnavailableException("Extension invocation retry was interrupted");
                    }
                }
            }
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

    private static boolean isRetryable(int statusCode) {
        return statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    private ExtensionRuntimePort.InvocationResult invokeOnce(String pluginId, int credentialGeneration, String extensionPoint, String operation,
            String idempotencyKey, String configurationJson, String requestJson, Duration deadline) {
        try {
            InspectContainerResponse container = docker.inspectContainerCmd(containerName(pluginId)).exec();
            if (!Boolean.TRUE.equals(container.getState().getRunning())) throw new ExtensionRuntimePort.ExtensionUnavailableException("Extension is stopped");
            URI endpoint = endpoint(container, "/v1/invoke");
            if (endpoint == null) throw new ExtensionRuntimePort.ExtensionUnavailableException("Extension has no runtime network address");
            String envelope = "{\"protocolVersion\":1,\"extensionPoint\":\"" + jsonEscape(extensionPoint)
                    + "\",\"operation\":\"" + jsonEscape(operation) + "\",\"idempotencyKey\":\""
                    + jsonEscape(idempotencyKey) + "\",\"configuration\":" + configurationJson
                    + ",\"payload\":" + requestJson + "}";
            byte[] body = envelope.getBytes(StandardCharsets.UTF_8);
            if (body.length > MAX_REQUEST_BYTES) throw new IllegalArgumentException("Extension invocation exceeds 1 MiB");
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(deadline).header("Authorization", "Bearer " + encryption.deriveExtensionCredential(pluginId, credentialGeneration))
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

    private URI endpoint(InspectContainerResponse container, String path) {
        if (hostLoopbackTestAccess) {
            var bindings = container.getNetworkSettings() == null || container.getNetworkSettings().getPorts() == null
                    ? null : container.getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(port));
            if (bindings == null || bindings.length == 0 || bindings[0].getHostPortSpec() == null) return null;
            return URI.create("http://127.0.0.1:" + bindings[0].getHostPortSpec() + path);
        }
        var networks = container.getNetworkSettings().getNetworks();
        var networkInfo = networks == null ? null : networks.get(network);
        String address = networkInfo == null ? null : networkInfo.getIpAddress();
        return address == null || address.isBlank() ? null : URI.create("http://" + address + ":" + port + path);
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
