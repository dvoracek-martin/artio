package io.artio.api.extensions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.artio.api.security.EncryptionService;
import io.artio.extensions.PluginConfigurationValidator;
import io.artio.extensions.PluginManifest;
import io.artio.extensions.PluginManifestValidator;
import io.artio.extensions.ExtensionRuntimePort;
import io.artio.extensions.PluginDependencyPolicy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only manifest registry and schema-backed configuration. It deliberately does not run images. */
@RestController
@RequestMapping("/api/admin/extensions")
@Transactional
public class ExtensionManagementController {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };
    private final PluginManifestValidator manifestValidator;
    private final PluginConfigurationValidator configurationValidator;
    private final EncryptionService encryption;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String coreVersion;
    private final ObjectProvider<ExtensionRuntimePort> dockerRuntime;

    public ExtensionManagementController(PluginManifestValidator manifestValidator,
            PluginConfigurationValidator configurationValidator, EncryptionService encryption,
            JdbcTemplate jdbc, ObjectMapper mapper,
            @Value("${artio.extensions.core-version:0.1.0}") String coreVersion,
            ObjectProvider<ExtensionRuntimePort> dockerRuntime) {
        this.manifestValidator = manifestValidator;
        this.configurationValidator = configurationValidator;
        this.encryption = encryption;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.coreVersion = coreVersion;
        this.dockerRuntime = dockerRuntime;
    }

    @PostMapping("/validate")
    @Transactional(readOnly = true)
    public ManifestValidationResponse validate(@RequestBody PluginManifest manifest) {
        return validateManifest(manifest, registeredPluginIds());
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<PluginSummary> findAll() {
        return jdbc.query("SELECT plugin_id,manifest,lifecycle_state,configuration_version,encrypted_secrets FROM extension_plugins WHERE lifecycle_state<>'UNINSTALLED' ORDER BY plugin_id",
                (rs, row) -> {
                    PluginManifest manifest = read(rs.getString("manifest"), PluginManifest.class);
                    Map<String, String> secrets = read(rs.getString("encrypted_secrets"), STRING_MAP);
                    return new PluginSummary(manifest.id(), manifest.name(), manifest.version(), rs.getString("lifecycle_state"),
                            rs.getInt("configuration_version"), Set.copyOf(secrets.keySet()));
                });
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody PluginManifest manifest, JwtAuthenticationToken actor) {
        if (exists(manifest.id())) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Plugin is already registered"));
        ManifestValidationResponse result = validateManifest(manifest, registeredPluginIds());
        if (!result.valid()) return ResponseEntity.badRequest().body(result);
        jdbc.update("INSERT INTO extension_plugins(plugin_id,manifest,image_reference,lifecycle_state) VALUES (?,?::jsonb,?,'VALIDATED')",
                manifest.id(), write(manifest), manifest.image());
        audit(manifest.id(), actor, "REGISTER", Map.of("version", manifest.version(), "image", manifest.image()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new PluginView(manifest, "VALIDATED", Map.of(), Set.of(), 0));
    }

    @GetMapping("/{pluginId}")
    @Transactional(readOnly = true)
    public PluginView findById(@PathVariable String pluginId) {
        PluginRow row = findPlugin(pluginId);
        return view(row);
    }

    @PostMapping("/{pluginId}/install")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ResponseEntity<?> install(@PathVariable String pluginId, JwtAuthenticationToken actor) {
        PluginRow row = findPlugin(pluginId);
        if (!Set.of("CONFIGURED", "INSTALLED", "DISABLED").contains(row.lifecycleState())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Plugin must be configured or stopped before installation"));
        }
        List<String> errors = manifestValidator.validate(row.manifest(), coreVersion, registeredPluginIds());
        errors = new java.util.ArrayList<>(errors);
        errors.addAll(dependencyErrors(row.manifest(), false));
        if (!errors.isEmpty()) return ResponseEntity.badRequest().body(new ManifestValidationResponse(false, coreVersion, errors));
        runtime().install(row.manifest());
        jdbc.update("UPDATE extension_plugins SET lifecycle_state='INSTALLED',updated_at=now() WHERE plugin_id=?", pluginId);
        audit(pluginId, actor, "INSTALL", Map.of("image", row.manifest().image()));
        return ResponseEntity.ok(Map.of("pluginId", pluginId, "lifecycleState", "INSTALLED"));
    }

    @PostMapping("/{pluginId}/enable")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ResponseEntity<?> enable(@PathVariable String pluginId, JwtAuthenticationToken actor) {
        PluginRow row = findPlugin(pluginId);
        if (!Set.of("INSTALLED", "DISABLED").contains(row.lifecycleState())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Plugin must be installed and stopped before enabling"));
        }
        List<String> dependencyErrors = dependencyErrors(row.manifest(), true);
        if (!dependencyErrors.isEmpty()) return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "Plugin dependencies are not ready", "errors", dependencyErrors));
        runtime().enable(row.manifest());
        jdbc.update("UPDATE extension_plugins SET lifecycle_state='ENABLED',updated_at=now() WHERE plugin_id=?", pluginId);
        audit(pluginId, actor, "ENABLE", Map.of("image", row.manifest().image()));
        return ResponseEntity.ok(Map.of("pluginId", pluginId, "lifecycleState", "ENABLED"));
    }

    @PostMapping("/{pluginId}/disable")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ResponseEntity<?> disable(@PathVariable String pluginId, JwtAuthenticationToken actor) {
        findPlugin(pluginId);
        List<PluginManifest> enabled = jdbc.query("SELECT manifest FROM extension_plugins WHERE lifecycle_state='ENABLED' AND plugin_id<>?",
                (rs, row) -> read(rs.getString("manifest"), PluginManifest.class), pluginId);
        List<String> dependents = PluginDependencyPolicy.enabledDependents(pluginId, enabled);
        if (!dependents.isEmpty()) return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "Enabled plugins depend on this plugin", "dependents", dependents));
        runtime().disable(pluginId);
        jdbc.update("UPDATE extension_plugins SET lifecycle_state='DISABLED',updated_at=now() WHERE plugin_id=?", pluginId);
        audit(pluginId, actor, "DISABLE", Map.of());
        return ResponseEntity.ok(Map.of("pluginId", pluginId, "lifecycleState", "DISABLED"));
    }

    @GetMapping("/{pluginId}/health")
    @Transactional(readOnly = true, propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ExtensionRuntimePort.RuntimeHealth health(@PathVariable String pluginId) {
        PluginRow row = findPlugin(pluginId);
        return runtime().health(pluginId, row.manifest().healthPath());
    }

    @PostMapping("/{pluginId}/invoke")
    @Transactional(readOnly = true, propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public InvocationResponse invoke(@PathVariable String pluginId, @RequestBody InvocationRequest request,
                                     JwtAuthenticationToken actor) {
        PluginRow row = findPlugin(pluginId);
        if (!"ENABLED".equals(row.lifecycleState())) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, "Plugin is not enabled");
        }
        try {
            ExtensionRuntimePort.validateInvocation(request.extensionPoint(), request.operation(), request.idempotencyKey(),
                    write(request.payload()), java.time.Duration.ofMillis(request.deadlineMillis()));
        } catch (IllegalArgumentException invalidRequest) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, invalidRequest.getMessage());
        }
        if (!row.manifest().extensionPoints().contains(request.extensionPoint())) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Extension point is not declared by this plugin");
        }
        String payload = write(request.payload());
        try {
            var result = runtime().invoke(pluginId, request.extensionPoint(), request.operation(), request.idempotencyKey(),
                    payload, java.time.Duration.ofMillis(request.deadlineMillis()));
            JsonNode response = mapper.readTree(result.responseJson());
            audit(pluginId, actor, "INVOKE", Map.of("extensionPoint", request.extensionPoint(),
                    "operation", request.operation(), "statusCode", result.statusCode()));
            return new InvocationResponse(result.statusCode(), response);
        } catch (ExtensionRuntimePort.ExtensionTimeoutException timeout) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Extension deadline exceeded");
        } catch (ExtensionRuntimePort.ExtensionCallException | ExtensionRuntimePort.ExtensionProtocolException failure) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_GATEWAY, "Extension returned an invalid or unsuccessful response");
        } catch (ExtensionRuntimePort.ExtensionUnavailableException unavailable) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Extension is unavailable");
        } catch (JsonProcessingException invalidJson) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_GATEWAY, "Extension response was not valid JSON");
        } catch (IllegalArgumentException invalidRequest) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, invalidRequest.getMessage());
        }
    }

    @PutMapping("/{pluginId}/configuration")
    public ResponseEntity<?> saveConfiguration(@PathVariable String pluginId, @RequestBody Map<String, Object> submitted,
                                               JwtAuthenticationToken actor) {
        PluginRow row = findPlugin(pluginId);
        if ("ENABLED".equals(row.lifecycleState())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Disable the plugin before changing its configuration"));
        }
        Map<String, String> encrypted = row.encryptedSecrets();
        var validation = configurationValidator.validate(row.manifest(), submitted, encrypted.keySet());
        if (!validation.valid()) return ResponseEntity.badRequest().body(new ConfigurationErrorResponse(validation.errors()));

        Map<String, String> updatedSecrets = new HashMap<>(encrypted);
        validation.newSecrets().forEach((key, value) -> updatedSecrets.put(key, encryption.encrypt(value)));
        String publicJson = write(validation.publicValues());
        String secretJson = write(updatedSecrets);
        jdbc.update("UPDATE extension_plugins SET public_configuration=?::jsonb,encrypted_secrets=?::jsonb,lifecycle_state='CONFIGURED',configuration_version=configuration_version+1,updated_at=now() WHERE plugin_id=?",
                publicJson, secretJson, pluginId);
        List<String> changedPublicFields = validation.publicValues().keySet().stream().sorted().toList();
        List<String> configuredSecretFields = updatedSecrets.keySet().stream().sorted().toList();
        audit(pluginId, actor, "CONFIGURE", Map.of("publicFields", changedPublicFields, "configuredSecretFields", configuredSecretFields));
        return ResponseEntity.ok(new PluginView(row.manifest(), "CONFIGURED", validation.publicValues(),
                Set.copyOf(updatedSecrets.keySet()), row.configurationVersion() + 1));
    }

    @GetMapping("/{pluginId}/audit")
    @Transactional(readOnly = true)
    public List<AuditView> audit(@PathVariable String pluginId) {
        findPlugin(pluginId);
        return jdbc.query("SELECT actor_user_id,action,details,occurred_at FROM extension_audit_events WHERE plugin_id=? ORDER BY occurred_at DESC",
                (rs, row) -> new AuditView(rs.getObject("actor_user_id", UUID.class), rs.getString("action"),
                        read(rs.getString("details"), OBJECT_MAP), rs.getTimestamp("occurred_at").toInstant()), pluginId);
    }

    private ManifestValidationResponse validateManifest(PluginManifest manifest, Set<String> installed) {
        List<String> errors = manifestValidator.validate(manifest, coreVersion, installed);
        return new ManifestValidationResponse(errors.isEmpty(), coreVersion, errors);
    }

    private Set<String> registeredPluginIds() {
        return Set.copyOf(jdbc.queryForList("SELECT plugin_id FROM extension_plugins WHERE lifecycle_state<>'UNINSTALLED'", String.class));
    }

    private List<String> dependencyErrors(PluginManifest manifest, boolean requireEnabled) {
        Map<String, PluginDependencyPolicy.InstalledPlugin> installed = new HashMap<>();
        jdbc.query("SELECT plugin_id,manifest,lifecycle_state FROM extension_plugins WHERE lifecycle_state<>'UNINSTALLED'",
                rs -> {
                    PluginManifest dependency = read(rs.getString("manifest"), PluginManifest.class);
                    installed.put(rs.getString("plugin_id"),
                            new PluginDependencyPolicy.InstalledPlugin(dependency.version(), rs.getString("lifecycle_state")));
                });
        return PluginDependencyPolicy.validate(manifest, installed, requireEnabled);
    }

    private boolean exists(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM extension_plugins WHERE plugin_id=?)", Boolean.class, id));
    }

    private PluginRow findPlugin(String id) {
        return jdbc.query("SELECT manifest,public_configuration,encrypted_secrets,lifecycle_state,configuration_version FROM extension_plugins WHERE plugin_id=?",
                (rs, row) -> new PluginRow(read(rs.getString("manifest"), PluginManifest.class),
                        read(rs.getString("public_configuration"), OBJECT_MAP), read(rs.getString("encrypted_secrets"), STRING_MAP),
                        rs.getString("lifecycle_state"), rs.getInt("configuration_version")), id)
                .stream().findFirst().orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Plugin is not registered"));
    }

    private PluginView view(PluginRow row) {
        return new PluginView(row.manifest(), row.lifecycleState(), row.publicConfiguration(),
                Set.copyOf(row.encryptedSecrets().keySet()), row.configurationVersion());
    }

    private void audit(String pluginId, JwtAuthenticationToken actor, String action, Map<String, Object> details) {
        jdbc.update("INSERT INTO extension_audit_events(id,plugin_id,actor_user_id,action,details) VALUES (?,?,?, ?,?::jsonb)",
                UUID.randomUUID(), pluginId, UUID.fromString(actor.getToken().getSubject()), action, write(details));
    }

    private ExtensionRuntimePort runtime() {
        ExtensionRuntimePort runtime = dockerRuntime.getIfAvailable();
        if (runtime == null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Docker extension runtime is disabled");
        return runtime;
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Extension metadata could not be serialized", exception); }
    }

    private <T> T read(String json, Class<T> type) {
        try { return mapper.readValue(json, type); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Stored extension metadata is invalid", exception); }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try { return mapper.readValue(json, type); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Stored extension metadata is invalid", exception); }
    }

    private record PluginRow(PluginManifest manifest, Map<String, Object> publicConfiguration,
                             Map<String, String> encryptedSecrets, String lifecycleState, int configurationVersion) { }
    public record ManifestValidationResponse(boolean valid, String coreVersion, List<String> errors) {
        public ManifestValidationResponse { errors = List.copyOf(errors); }
    }
    public record PluginSummary(String id, String name, String version, String lifecycleState,
                                int configurationVersion, Set<String> configuredSecrets) { }
    public record PluginView(PluginManifest manifest, String lifecycleState, Map<String, Object> configuration,
                             Set<String> configuredSecrets, int configurationVersion) { }
    public record ConfigurationErrorResponse(List<String> errors) {
        public ConfigurationErrorResponse { errors = List.copyOf(errors); }
    }
    public record AuditView(UUID actorUserId, String action, Map<String, Object> details, java.time.Instant occurredAt) { }
    public record InvocationRequest(String extensionPoint, String operation, String idempotencyKey,
                                    JsonNode payload, long deadlineMillis) { }
    public record InvocationResponse(int statusCode, JsonNode payload) { }
}

