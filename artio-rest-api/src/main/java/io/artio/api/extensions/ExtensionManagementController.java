package io.artio.api.extensions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.security.EncryptionService;
import io.artio.extensions.PluginConfigurationValidator;
import io.artio.extensions.PluginManifest;
import io.artio.extensions.PluginManifestValidator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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

    public ExtensionManagementController(PluginManifestValidator manifestValidator,
            PluginConfigurationValidator configurationValidator, EncryptionService encryption,
            JdbcTemplate jdbc, ObjectMapper mapper,
            @Value("${artio.extensions.core-version:0.1.0}") String coreVersion) {
        this.manifestValidator = manifestValidator;
        this.configurationValidator = configurationValidator;
        this.encryption = encryption;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.coreVersion = coreVersion;
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

    @PutMapping("/{pluginId}/configuration")
    public ResponseEntity<?> saveConfiguration(@PathVariable String pluginId, @RequestBody Map<String, Object> submitted,
                                               JwtAuthenticationToken actor) {
        PluginRow row = findPlugin(pluginId);
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
}

