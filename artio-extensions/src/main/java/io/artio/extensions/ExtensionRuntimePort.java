package io.artio.extensions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/** Core-owned boundary to an isolated extension process; the Docker adapter is outside this module. */
public interface ExtensionRuntimePort {
    void install(PluginManifest manifest);
    default void update(PluginManifest manifest) { install(manifest); }
    void enable(PluginManifest manifest);
    default void enable(PluginManifest manifest, int credentialGeneration) { enable(manifest); }
    void disable(String pluginId);
    /** Remove the managed runtime for an already stopped plugin. */
    default void uninstall(String pluginId) { disable(pluginId); }
    default void rotateCredentials(PluginManifest manifest, int credentialGeneration, boolean restartIfEnabled) {
        if (restartIfEnabled) {
            disable(manifest.id());
            enable(manifest, credentialGeneration);
        }
    }
    RuntimeHealth health(String pluginId, String healthPath);
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

    record RuntimeHealth(boolean healthy, String status) { }
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
