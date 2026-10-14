package io.artio.api.extensions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.security.EncryptionService;
import io.artio.extensions.ExtensionRuntimePort;
import io.artio.extensions.PluginManifest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Invokes only registered, enabled plugins using their persisted schema configuration. */
@Service
public class ManagedExtensionInvoker {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };
    private static final Duration DEADLINE = Duration.ofSeconds(10);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final EncryptionService encryption;
    private final ObjectProvider<ExtensionRuntimePort> runtimeProvider;

    public ManagedExtensionInvoker(JdbcTemplate jdbc, ObjectMapper mapper, EncryptionService encryption,
            ObjectProvider<ExtensionRuntimePort> runtimeProvider) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.encryption = encryption;
        this.runtimeProvider = runtimeProvider;
    }

    public JsonNode invoke(String pluginId, String extensionPoint, String operation, Object payload) {
        return invoke(pluginId, extensionPoint, operation, UUID.randomUUID().toString(), payload);
    }

    /** Invokes a plugin with a stable caller-owned key for provider operations that must be idempotent across retries. */
    public JsonNode invoke(String pluginId, String extensionPoint, String operation, String idempotencyKey, Object payload) {
        PluginRow plugin = jdbc.query("""
                SELECT manifest,public_configuration,encrypted_secrets,lifecycle_state,credential_generation
                FROM extension_plugins WHERE plugin_id=? AND lifecycle_state<>'UNINSTALLED'
                """, (rs, row) -> new PluginRow(read(rs.getString("manifest"), PluginManifest.class),
                read(rs.getString("public_configuration"), OBJECT_MAP), read(rs.getString("encrypted_secrets"), STRING_MAP),
                rs.getString("lifecycle_state"), rs.getInt("credential_generation")), pluginId)
                .stream().findFirst().orElseThrow(() -> new ExtensionRuntimePort.ExtensionUnavailableException("Plugin is not installed"));
        if (!"ENABLED".equals(plugin.lifecycleState())) throw new ExtensionRuntimePort.ExtensionUnavailableException("Plugin is not enabled");
        if (!plugin.manifest().extensionPoints().contains(extensionPoint)) throw new IllegalArgumentException("Plugin does not declare the required extension point");
        String requiredPermission = requiredPermission(extensionPoint, operation);
        if (!plugin.manifest().permissions().contains(requiredPermission)) {
            throw new IllegalArgumentException("Plugin does not declare the permission required for this operation");
        }
        Map<String, Object> configuration = new HashMap<>(plugin.publicConfiguration());
        plugin.encryptedSecrets().forEach((key, value) -> configuration.put(key, encryption.decrypt(value)));
        try {
            String configurationJson = mapper.writeValueAsString(configuration);
            String payloadJson = mapper.writeValueAsString(payload);
            ExtensionRuntimePort runtime = runtimeProvider.getIfAvailable();
            if (runtime == null) throw new ExtensionRuntimePort.ExtensionUnavailableException("Docker extension runtime is disabled");
            var result = runtime.invoke(pluginId, plugin.credentialGeneration(), extensionPoint, operation,
                    idempotencyKey, configurationJson, payloadJson, DEADLINE);
            return mapper.readTree(result.responseJson());
        } catch (ExtensionRuntimePort.ExtensionUnavailableException | ExtensionRuntimePort.ExtensionTimeoutException
                | ExtensionRuntimePort.ExtensionCallException | IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ExtensionRuntimePort.ExtensionProtocolException("Plugin returned an invalid response");
        }
    }

    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (Exception failure) { throw new IllegalStateException("Persisted plugin configuration is invalid", failure); }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try { return mapper.readValue(value, type); }
        catch (Exception failure) { throw new IllegalStateException("Persisted plugin configuration is invalid", failure); }
    }

    private static String requiredPermission(String extensionPoint, String operation) {
        return switch (extensionPoint) {
            case "catalog.search.v1", "catalog.enrichment.v1" -> "catalog.read";
            case "payment.provider.v1" -> "payment.process";
            case "notification.channel.v1" -> "notifications.send";
            case "shipping.provider.v1" -> Set.of("quote", "methods").contains(operation)
                    ? "shipping.quote" : "shipping.label";
            default -> throw new IllegalArgumentException("Unsupported plugin extension point");
        };
    }

    private record PluginRow(PluginManifest manifest, Map<String, Object> publicConfiguration,
            Map<String, String> encryptedSecrets, String lifecycleState, int credentialGeneration) { }
}
