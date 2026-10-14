package io.artio.api.extensions;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import io.artio.api.security.ExtensionCredentialService;
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

/** Restricted Docker runtime for digest-pinned extensions. The Docker daemon is an administrative trust boundary. */
public final class DockerExtensionRuntime implements ExtensionRuntimePort {
    private static final String EGRESS_PROXY = "http://extension-egress:3128";
    private final int port;
    private static final long MAX_REQUEST_BYTES = 1_048_576;
    private static final long MAX_RESPONSE_BYTES = 1_048_576;
    private final DockerClient docker;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String network;
    private final ExtensionCredentialService credentials;
    private final String egressProxy;
    private final boolean hostLoopbackTestAccess;
    private final java.util.concurrent.ConcurrentMap<String, ExtensionCircuitBreaker> circuits = new java.util.concurrent.ConcurrentHashMap<>();

    public DockerExtensionRuntime(DockerClient docker,
            @org.springframework.beans.factory.annotation.Value("${artio.extensions.docker.network:artio-extensions}") String network,
            @org.springframework.beans.factory.annotation.Value("${artio.extensions.docker.port:8080}") int port,
            ExtensionCredentialService credentials,
            @org.springframework.beans.factory.annotation.Value("${artio.extensions.docker.egress-proxy:http://extension-egress:3128}") String egressProxy) {
        this(docker, network, port, credentials, false, egressProxy);
    }

    DockerExtensionRuntime(DockerClient docker, String network, int port, ExtensionCredentialService credentials) {
        this(docker, network, port, credentials, false, EGRESS_PROXY);
    }

    DockerExtensionRuntime(DockerClient docker, String network, int port, ExtensionCredentialService credentials,
                           boolean hostLoopbackTestAccess) {
        this(docker, network, port, credentials, hostLoopbackTestAccess, EGRESS_PROXY);
    }

    DockerExtensionRuntime(DockerClient docker, String network, int port, ExtensionCredentialService credentials,
                           boolean hostLoopbackTestAccess, String egressProxy) {
        this.docker = docker;
        this.network = network;
        this.port = port;
        this.credentials = credentials;
        this.egressProxy = egressProxy;
        this.hostLoopbackTestAccess = hostLoopbackTestAccess;
    }

    public void install(PluginManifest manifest) {
        try {
            for (RuntimeService service : services(manifest)) {
                requirePinnedImage(service.image());
                docker.pullImageCmd(service.image()).start().awaitCompletion(120, TimeUnit.SECONDS);
            }
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
        enable(manifest, credentialGeneration, Map.of());
    }

    @Override
    public void enable(PluginManifest manifest, int credentialGeneration, Map<String, String> configuration) {
        if (credentialGeneration < 1) throw new IllegalArgumentException("Credential generation must be positive");
        try {
            for (RuntimeService service : servicesInStartupOrder(manifest)) {
                startService(manifest, service, credentialGeneration, configuration);
                awaitReady(manifest.id(), service.id(), service.healthPath());
            }
        } catch (RuntimeException failure) {
            disable(manifest.id());
            throw failure;
        }
    }

    private void startService(PluginManifest manifest, RuntimeService service, int credentialGeneration,
            Map<String, String> configuration) {
        String name = serviceName(manifest.id(), service.id());
        var environment = environment(manifest, service, credentialGeneration, configuration);
        try {
            InspectContainerResponse current = docker.inspectContainerCmd(name).exec();
            requireManaged(current, manifest.id());
            if (Boolean.TRUE.equals(current.getState().getRunning())) return;
            var existingEnvironment = current.getConfig() == null ? null : current.getConfig().getEnv();
            if (existingEnvironment != null && java.util.Arrays.equals(existingEnvironment, environment.toArray(String[]::new))) {
                docker.startContainerCmd(name).exec();
                return;
            }
            docker.removeContainerCmd(current.getId()).withForce(false).exec();
        } catch (NotFoundException ignored) {
            // Create only containers owned by this runtime; no mounts, published ports or caller-supplied options.
        }
        HostConfig host = restrictedHostConfig(network);
        if (hostLoopbackTestAccess && service.primary()) {
            var bindings = new com.github.dockerjava.api.model.Ports();
            bindings.bind(ExposedPort.tcp(service.port()), com.github.dockerjava.api.model.Ports.Binding.bindIp("127.0.0.1"));
            host.withPortBindings(bindings);
        }
        var create = docker.createContainerCmd(service.image()).withName(name).withHostConfig(host)
                .withUser("10001:10001").withExposedPorts(ExposedPort.tcp(service.port()))
                .withLabels(Map.of("io.artio.extension", manifest.id(), "io.artio.extension.service", service.id(),
                        "io.artio.extension.startup-order", Integer.toString(service.startupOrder()), "io.artio.managed", "true"));
        create.withEnv(environment);
        create.exec();
        docker.startContainerCmd(name).exec();
    }

    private java.util.List<String> environment(PluginManifest manifest, RuntimeService service,
            int credentialGeneration, Map<String, String> configuration) {
        return resolvedEnvironment(manifest, service.primary(), service.configurationBindings(),
                credentialGeneration, configuration, credentials, egressProxy);
    }

    static java.util.List<String> resolvedEnvironment(PluginManifest manifest, boolean primary,
            Map<String, String> bindings, int credentialGeneration, Map<String, String> configuration,
            io.artio.api.security.ExtensionCredentialService credentials) {
        return resolvedEnvironment(manifest, primary, bindings, credentialGeneration, configuration, credentials, EGRESS_PROXY);
    }

    static java.util.List<String> resolvedEnvironment(PluginManifest manifest, boolean primary,
            Map<String, String> bindings, int credentialGeneration, Map<String, String> configuration,
            io.artio.api.security.ExtensionCredentialService credentials, String egressProxy) {
        var values = new java.util.TreeMap<String, String>();
        if (egressProxy != null && !egressProxy.isBlank()) {
            values.put("HTTP_PROXY", egressProxy);
            values.put("HTTPS_PROXY", egressProxy);
            values.put("http_proxy", egressProxy);
            values.put("https_proxy", egressProxy);
            values.put("NO_PROXY", "localhost,127.0.0.1,::1");
            values.put("no_proxy", "localhost,127.0.0.1,::1");
        }
        if (primary) {
            values.put("ARTIO_EXTENSION_TOKEN", credentials.derive(manifest.id(), credentialGeneration));
            values.put("ARTIO_EXTENSION_API_VERSION", "1");
        }
        for (var binding : bindings.entrySet()) {
            String value = configuration.get(binding.getValue());
            PluginManifest.ConfigField field = manifest.configuration().get(binding.getValue());
            if (value == null) {
                if (field != null && field.required()) throw new IllegalStateException("Required plugin configuration is missing");
                continue;
            }
            if (value.length() > 10_000) throw new IllegalArgumentException("Plugin configuration value is too large for a container environment");
            values.put(binding.getKey(), value);
        }
        if (values.size() > 100) throw new IllegalArgumentException("Plugin container environment exceeds its limit");
        long environmentBytes = values.entrySet().stream().mapToLong(entry -> entry.getKey().getBytes(StandardCharsets.UTF_8).length
                + entry.getValue().getBytes(StandardCharsets.UTF_8).length).sum();
        if (environmentBytes > 900_000) throw new IllegalArgumentException("Plugin container environment exceeds its size limit");
        return values.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toList();
    }

    static HostConfig restrictedHostConfig(String network) {
        return HostConfig.newHostConfig().withNetworkMode(network).withMemory(268_435_456L)
                .withNanoCPUs(1_000_000_000L).withPidsLimit(128L).withReadonlyRootfs(true)
                .withCapDrop(Capability.ALL).withSecurityOpts(java.util.List.of("no-new-privileges:true"))
                .withTmpFs(Map.of("/tmp", "rw,noexec,nosuid,size=16m"));
    }

    public void disable(String pluginId) {
        var containers = new java.util.ArrayList<>(managedContainers(pluginId));
        containers.sort(java.util.Comparator.comparingInt(DockerExtensionRuntime::startupOrder).reversed());
        for (InspectContainerResponse current : containers) {
            if (Boolean.TRUE.equals(current.getState().getRunning())) docker.stopContainerCmd(current.getId()).withTimeout(10).exec();
        }
    }

    @Override
    public void uninstall(String pluginId) {
        var containers = managedContainers(pluginId);
        for (InspectContainerResponse current : containers) {
            requireManaged(current, pluginId);
            if (Boolean.TRUE.equals(current.getState().getRunning())) {
                throw new IllegalStateException("Disable the extension before uninstalling it");
            }
        }
        for (InspectContainerResponse current : containers) {
            docker.removeContainerCmd(current.getId()).withForce(false).exec();
        }
    }

    @Override
    public void rotateCredentials(PluginManifest manifest, int credentialGeneration, boolean restartIfEnabled) {
        rotateCredentials(manifest, credentialGeneration, restartIfEnabled, Map.of());
    }

    @Override
    public void rotateCredentials(PluginManifest manifest, int credentialGeneration, boolean restartIfEnabled,
            Map<String, String> configuration) {
        var containers = managedContainers(manifest.id());
        for (InspectContainerResponse current : containers) requireManaged(current, manifest.id());
        for (InspectContainerResponse current : containers) {
            if (Boolean.TRUE.equals(current.getState().getRunning())) docker.stopContainerCmd(current.getId()).withTimeout(10).exec();
        }
        for (InspectContainerResponse current : containers) {
            requireManaged(current, manifest.id());
            docker.removeContainerCmd(current.getId()).withForce(true).exec();
        }
        if (restartIfEnabled) enable(manifest, credentialGeneration, configuration);
    }

    private void removeStoppedManagedContainer(String pluginId) {
        var containers = managedContainers(pluginId);
        for (InspectContainerResponse current : containers) {
            requireManaged(current, pluginId);
            if (Boolean.TRUE.equals(current.getState().getRunning())) {
                throw new IllegalStateException("Disable the extension before updating its image");
            }
        }
        for (InspectContainerResponse current : containers) {
            docker.removeContainerCmd(current.getId()).withForce(false).exec();
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
                    .timeout(deadline).header("Authorization", "Bearer " + credentials.derive(pluginId, credentialGeneration))
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

    private void awaitReady(String pluginId, String serviceId, String healthPath) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        do {
            if (serviceHealth(pluginId, serviceId, healthPath).healthy()) return;
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

    private ExtensionRuntimePort.RuntimeHealth serviceHealth(String pluginId, String serviceId, String healthPath) {
        try {
            String name = serviceId.equals("main") ? containerName(pluginId) : serviceName(pluginId, serviceId);
            InspectContainerResponse container = docker.inspectContainerCmd(name).exec();
            requireManaged(container, pluginId);
            if (!Boolean.TRUE.equals(container.getState().getRunning())) return new ExtensionRuntimePort.RuntimeHealth(false, "STOPPED");
            var exposed = container.getConfig() == null ? null : container.getConfig().getExposedPorts();
            if (exposed == null || exposed.length != 1) return new ExtensionRuntimePort.RuntimeHealth(false, "NO_EXPOSED_PORT");
            URI address = endpoint(container, safeHealthPath(healthPath), exposed[0].getPort());
            if (address == null) return new ExtensionRuntimePort.RuntimeHealth(false, "NO_NETWORK_ADDRESS");
            var response = http.send(HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding());
            return new ExtensionRuntimePort.RuntimeHealth(response.statusCode() >= 200 && response.statusCode() < 300, "HTTP_" + response.statusCode());
        } catch (NotFoundException missing) {
            return new ExtensionRuntimePort.RuntimeHealth(false, "NOT_INSTALLED");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ExtensionRuntimePort.RuntimeHealth(false, "INTERRUPTED");
        } catch (Exception failure) {
            return new ExtensionRuntimePort.RuntimeHealth(false, "UNAVAILABLE");
        }
    }

    private URI endpoint(InspectContainerResponse container, String path) {
        return endpoint(container, path, port);
    }

    private URI endpoint(InspectContainerResponse container, String path, int servicePort) {
        if (hostLoopbackTestAccess) {
            var bindings = container.getNetworkSettings() == null || container.getNetworkSettings().getPorts() == null
                    ? null : container.getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(servicePort));
            if (bindings == null || bindings.length == 0 || bindings[0].getHostPortSpec() == null) return null;
            return URI.create("http://127.0.0.1:" + bindings[0].getHostPortSpec() + path);
        }
        var networks = container.getNetworkSettings().getNetworks();
        var networkInfo = networks == null ? null : networks.get(network);
        String address = networkInfo == null ? null : networkInfo.getIpAddress();
        return address == null || address.isBlank() ? null : URI.create("http://" + address + ":" + servicePort + path);
    }

    private java.util.List<InspectContainerResponse> managedContainers(String pluginId) {
        return docker.listContainersCmd().withShowAll(true).exec().stream()
                .filter(container -> container.getLabels() != null && pluginId.equals(container.getLabels().get("io.artio.extension")))
                .map(container -> docker.inspectContainerCmd(container.getId()).exec()).toList();
    }

    private static void requireManaged(InspectContainerResponse container, String pluginId) {
        var labels = container.getConfig() == null ? null : container.getConfig().getLabels();
        if (labels == null || !"true".equals(labels.get("io.artio.managed")) || !pluginId.equals(labels.get("io.artio.extension")))
            throw new IllegalStateException("Refusing to manage an unmanaged extension container");
    }

    private java.util.List<RuntimeService> services(PluginManifest manifest) {
        var result = new java.util.ArrayList<RuntimeService>();
        result.add(new RuntimeService("main", manifest.image(), port, manifest.healthPath(), true, manifest.configurationBindings(), 0));
        for (var service : manifest.services()) result.add(new RuntimeService(service.id(), service.image(), service.port(), service.healthPath(), false, service.configurationBindings(), 0));
        return java.util.List.copyOf(result);
    }

    private java.util.List<RuntimeService> servicesInStartupOrder(PluginManifest manifest) {
        var byId = new java.util.LinkedHashMap<String, RuntimeService>();
        for (RuntimeService service : services(manifest)) byId.put(service.id(), service);
        var dependencies = new java.util.LinkedHashMap<String, java.util.Set<String>>();
        dependencies.put("main", java.util.Set.of());
        manifest.services().forEach(service -> dependencies.put(service.id(), new java.util.LinkedHashSet<>(service.dependsOn())));
        var ordered = new java.util.ArrayList<RuntimeService>();
        var visited = new java.util.HashSet<String>();
        var visiting = new java.util.HashSet<String>();
        for (String id : byId.keySet()) visitService(id, byId, dependencies, visited, visiting, ordered);
        var indexed = new java.util.ArrayList<RuntimeService>();
        for (int index = 0; index < ordered.size(); index++) {
            RuntimeService service = ordered.get(index);
            indexed.add(new RuntimeService(service.id(), service.image(), service.port(), service.healthPath(),
                    service.primary(), service.configurationBindings(), index));
        }
        return java.util.List.copyOf(indexed);
    }

    private static int startupOrder(InspectContainerResponse container) {
        var labels = container.getConfig() == null ? null : container.getConfig().getLabels();
        try { return Integer.parseInt(labels == null ? null : labels.get("io.artio.extension.startup-order")); }
        catch (RuntimeException legacyContainer) { return -1; }
    }

    private static void visitService(String id, Map<String, RuntimeService> services,
            Map<String, java.util.Set<String>> dependencies, java.util.Set<String> visited,
            java.util.Set<String> visiting, java.util.List<RuntimeService> ordered) {
        if (visited.contains(id)) return;
        if (!visiting.add(id)) throw new IllegalArgumentException("Bundle service startup dependencies contain a cycle");
        for (String dependency : dependencies.getOrDefault(id, java.util.Set.of())) {
            if (!services.containsKey(dependency)) throw new IllegalArgumentException("Bundle service depends on an unknown service");
            visitService(dependency, services, dependencies, visited, visiting, ordered);
        }
        visiting.remove(id);
        visited.add(id);
        ordered.add(services.get(id));
    }

    static String serviceName(String pluginId, String serviceId) {
        if (serviceId == null || !serviceId.matches("[a-z][a-z0-9-]{0,30}")) throw new IllegalArgumentException("Invalid plugin service id");
        if (serviceId.equals("main")) return containerName(pluginId);
        String primary = containerName(pluginId);
        return "artio-svc-" + primary.substring(primary.length() - 12) + "-" + serviceId;
    }

    private record RuntimeService(String id, String image, int port, String healthPath, boolean primary,
                                  Map<String, String> configurationBindings, int startupOrder) { }

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
