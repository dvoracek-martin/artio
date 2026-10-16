package io.artio.extensions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.io.InputStream;

/** Core-owned boundary to an isolated extension process; the Docker adapter is outside this module. */
public interface ExtensionRuntimePort {
    /** Imports an already verified OCI layout into the private Docker cache without using registry credentials. */
    default void importLocalArchive(Path archive, Map<String, java.util.List<String>> manifestConfigDigests) {
        try (InputStream input = Files.newInputStream(archive)) {
            importLocalArchive(input, manifestConfigDigests);
        } catch (IOException failure) {
            throw new IllegalStateException("Local OCI archive could not be read", failure);
        }
    }
    /** Internal controller boundary; callers must authenticate and validate the archive before invoking it. */
    default void importLocalArchive(InputStream archive, Map<String, java.util.List<String>> manifestConfigDigests) {
        throw new ExtensionUnavailableException("Local OCI archive import is unavailable");
    }
    void install(PluginManifest manifest);
    default void install(PluginManifest manifest, Map<String, RegistryAuth> registryCredentials) {
        install(manifest);
    }
    /** Installs a package release for one isolated runtime identity. v1 adapters keep the legacy package identity. */
    default void installInstance(String runtimeIdentity, PluginManifest manifest) { install(manifest); }
    default void installInstance(
            String runtimeIdentity, PluginManifest manifest, Map<String, RegistryAuth> registryCredentials) {
        installInstance(runtimeIdentity, manifest);
    }
    default void update(PluginManifest manifest) { install(manifest); }
    default void update(PluginManifest manifest, Map<String, RegistryAuth> registryCredentials) {
        update(manifest);
    }
    void enable(PluginManifest manifest);
    default void enable(PluginManifest manifest, int credentialGeneration) { enable(manifest); }
    default void enable(PluginManifest manifest, int credentialGeneration, Map<String, String> configuration) {
        enable(manifest, credentialGeneration);
    }
    default void enableInstance(String runtimeIdentity, PluginManifest manifest, int credentialGeneration,
            Map<String, String> configuration) { enable(manifest, credentialGeneration, configuration); }
    void disable(String pluginId);
    default void disableInstance(String runtimeIdentity, String pluginId) { disable(pluginId); }
    /** Remove the managed runtime for an already stopped plugin. */
    default void uninstall(String pluginId) { disable(pluginId); }
    default void uninstallInstance(String runtimeIdentity, String pluginId) { uninstall(pluginId); }
    default void rotateCredentials(PluginManifest manifest, int credentialGeneration, boolean restartIfEnabled) {
        if (restartIfEnabled) {
            disable(manifest.id());
            enable(manifest, credentialGeneration);
        }
    }
    default void rotateCredentials(PluginManifest manifest, int credentialGeneration, boolean restartIfEnabled,
            Map<String, String> configuration) {
        rotateCredentials(manifest, credentialGeneration, restartIfEnabled);
    }
    RuntimeHealth health(String pluginId, String healthPath);
    default RuntimeHealth health(PluginManifest manifest) { return health(manifest.id(), manifest.healthPath()); }
    default RuntimeHealth healthInstance(String runtimeIdentity, PluginManifest manifest) { return health(manifest); }
    default TelemetryResponse forwardOtlp(String pluginId, String signal, byte[] payload) {
        throw new ExtensionUnavailableException("OTLP receiver is unavailable");
    }
    default UiProxyResponse proxyObservability(String pluginId, String method, String path, String query) {
        throw new ExtensionUnavailableException("Observability UI is unavailable");
    }
    default UiProxyResponse proxyObservability(String pluginId, String method, String path, String query,
            String userId, String email) {
        return proxyObservability(pluginId, method, path, query);
    }
    InvocationResult invoke(String pluginId, String extensionPoint, String operation, String idempotencyKey,
                            String configurationJson, String requestJson, Duration deadline);
    default InvocationResult invoke(String pluginId, int credentialGeneration, String extensionPoint, String operation,
            String idempotencyKey, String configurationJson, String requestJson, Duration deadline) {
        return invoke(pluginId, extensionPoint, operation, idempotencyKey, configurationJson, requestJson, deadline);
    }

    static void validateInvocation(String extensionPoint, String operation, String idempotencyKey,
                                   String configurationJson, String requestJson, Duration deadline) {
        if (extensionPoint == null || !extensionPoint.matches("[a-z][a-z0-9.-]{1,120}\\.v[1-9][0-9]*"))
            throw new IllegalArgumentException("Extension point must be a versioned identifier");
        if (operation == null || !operation.matches("[a-z][a-zA-Z0-9_.-]{0,63}"))
            throw new IllegalArgumentException("Invalid extension operation");
        try {
            if (!UUID.fromString(idempotencyKey).toString().equalsIgnoreCase(idempotencyKey))
                throw new IllegalArgumentException("Idempotency key must use canonical UUID form");
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Idempotency key must be a canonical UUID", invalid); }
        if (requestJson == null || requestJson.isBlank() || requestJson.getBytes(StandardCharsets.UTF_8).length > 1_048_576
                || configurationJson == null || configurationJson.isBlank()
                || configurationJson.getBytes(StandardCharsets.UTF_8).length > 1_048_576)
            throw new IllegalArgumentException("Extension configuration and invocation must each be between 1 byte and 1 MiB");
        String envelope = "{\"protocolVersion\":1,\"extensionPoint\":\"" + extensionPoint + "\",\"operation\":\""
                + operation + "\",\"idempotencyKey\":\"" + idempotencyKey + "\",\"configuration\":"
                + configurationJson + ",\"payload\":" + requestJson + "}";
        if (envelope.getBytes(StandardCharsets.UTF_8).length > 1_048_576)
            throw new IllegalArgumentException("Extension invocation envelope exceeds 1 MiB");
        if (deadline == null || deadline.compareTo(Duration.ofMillis(100)) < 0 || deadline.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("Extension deadline must be between 100 ms and 30 s");
    }

    record RuntimeHealth(boolean healthy, String status, Map<String, String> services) {
        public RuntimeHealth { services = services == null ? Map.of() : Map.copyOf(services); }
        public RuntimeHealth(boolean healthy, String status) { this(healthy, status, Map.of()); }
    }
    record RegistryAuth(String username, String password) {
        public RegistryAuth {
            if (username == null || username.isBlank() || password == null || password.isBlank())
                throw new IllegalArgumentException("Registry authentication is invalid");
        }
    }
    record TelemetryResponse(int statusCode, String contentType, byte[] body) {
        public TelemetryResponse { body = body == null ? new byte[0] : body.clone(); }
        @Override public byte[] body() { return body.clone(); }
    }
    record UiProxyResponse(int statusCode, String contentType, Map<String, String> headers, byte[] body) {
        public UiProxyResponse {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            body = body == null ? new byte[0] : body.clone();
        }
        @Override public byte[] body() { return body.clone(); }
    }
    record InvocationResult(int statusCode, String responseJson) { }
    class ExtensionUnavailableException extends RuntimeException { public ExtensionUnavailableException(String message) { super(message); } }
    class ExtensionTimeoutException extends RuntimeException { public ExtensionTimeoutException(String message) { super(message); } }
    class ExtensionProtocolException extends RuntimeException { public ExtensionProtocolException(String message) { super(message); } }
    class ExtensionCallException extends RuntimeException {
        private final int statusCode;
        public ExtensionCallException(int statusCode) { super("Extension returned an unsuccessful status"); this.statusCode = statusCode; }
        public int statusCode() { return statusCode; }
    }
}
