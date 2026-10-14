package io.artio.api.extensions;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.artio.api.security.EncryptionService;
import io.artio.api.distributions.DistributionPluginProvisioningService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Admin-only manifest registry and schema-backed configuration. It deliberately does not run images. */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
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
    private final DistributionPluginProvisioningService provisioning;

    public ExtensionManagementController(PluginManifestValidator manifestValidator,
            PluginConfigurationValidator configurationValidator, EncryptionService encryption,
            JdbcTemplate jdbc, ObjectMapper mapper,
            @Value("${artio.extensions.core-version:0.1.0}") String coreVersion,
            ObjectProvider<ExtensionRuntimePort> dockerRuntime, DistributionPluginProvisioningService provisioning) {
        this.manifestValidator = manifestValidator;
        this.configurationValidator = configurationValidator;
        this.encryption = encryption;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.coreVersion = coreVersion;
        this.dockerRuntime = dockerRuntime;
        this.provisioning = provisioning;
    }

    @PostMapping("/validate")
    @Transactional(readOnly = true)
    public ManifestValidationResponse validate(@RequestBody PluginManifest manifest) {
        return validateManifest(manifest, registeredPluginIds());
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<PluginSummary> findAll(@RequestParam(required = false) String country) {
        if (country != null && (!country.matches("[A-Z]{2}") || !Set.of(java.util.Locale.getISOCountries()).contains(country))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "country must be an ISO 3166-1 alpha-2 code");
        }
        List<PluginSummary> plugins = jdbc.query("SELECT plugin_id,manifest,lifecycle_state,configuration_version,encrypted_secrets FROM extension_plugins WHERE lifecycle_state<>'UNINSTALLED' ORDER BY plugin_id",
                (rs, row) -> {
                    PluginManifest manifest = read(rs.getString("manifest"), PluginManifest.class);
                    Map<String, String> secrets = read(rs.getString("encrypted_secrets"), STRING_MAP);
                    return new PluginSummary(manifest.id(), manifest.name(), manifest.version(), rs.getString("lifecycle_state"),
                            rs.getInt("configuration_version"), Set.copyOf(secrets.keySet()), manifest.geographicAvailability());
                });
        return country == null ? plugins : plugins.stream()
                .filter(plugin -> plugin.geographicAvailability() == null || plugin.geographicAvailability().appliesTo(country))
                .toList();
    }

    @GetMapping("/catalog")
    @Transactional(readOnly = true)
    public List<DistributionPluginProvisioningService.CatalogItem> catalog(
            @RequestParam(required = false) String country) {
        return provisioning.catalog(country);
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody PluginManifest manifest, JwtAuthenticationToken actor) {
        if (exists(manifest.id())) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Plugin is already registered"));
        ManifestValidationResponse result = validateManifest(manifest, registeredPluginIds());
        if (!result.valid()) return ResponseEntity.badRequest().body(result);
        jdbc.update("INSERT INTO extension_plugins(plugin_id,manifest,image_reference,lifecycle_state) VALUES (?,?::jsonb,?,'VALIDATED')",
                manifest.id(), write(manifest), manifest.image());
        jdbc.update("INSERT INTO extension_plugin_releases(plugin_id,version,image_reference,manifest) VALUES (?,?,?,?::jsonb)",
                manifest.id(), manifest.version(), manifest.image(), write(manifest));
        refreshProvisioning(manifest.id());
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
        if (!Set.of("CONFIGURED", "INSTALLED", "DISABLED", "FAILED").contains(row.lifecycleState())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Plugin must be configured or stopped before installation"));
        }
        List<String> errors = manifestValidator.validate(row.manifest(), coreVersion, registeredPluginIds());
        errors = new java.util.ArrayList<>(errors);
        errors.addAll(dependencyErrors(row.manifest(), false));
        if (!errors.isEmpty()) return ResponseEntity.badRequest().body(new ManifestValidationResponse(false, coreVersion, errors));
        ExtensionRuntimePort runtime = runtime();
        try {
            runtime.install(row.manifest());
        } catch (RuntimeException failure) {
            jdbc.update("UPDATE extension_plugins SET lifecycle_state='FAILED',updated_at=now() WHERE plugin_id=?", pluginId);
            refreshProvisioning(pluginId);
            audit(pluginId, actor, "INSTALL_FAILED", Map.of("errorClass", failure.getClass().getSimpleName()));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "Extension image could not be installed"));
        }
        jdbc.update("UPDATE extension_plugins SET lifecycle_state='INSTALLED',updated_at=now() WHERE plugin_id=?", pluginId);
        refreshProvisioning(pluginId);
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
        ExtensionRuntimePort runtime = runtime();
        try {
            runtime.enable(row.manifest(), row.credentialGeneration(), runtimeConfiguration(row));
        } catch (RuntimeException failure) {
            jdbc.update("UPDATE extension_plugins SET lifecycle_state='FAILED',updated_at=now() WHERE plugin_id=?", pluginId);
            refreshProvisioning(pluginId);
            audit(pluginId, actor, "ENABLE_FAILED", Map.of("errorClass", failure.getClass().getSimpleName()));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "Extension did not become available"));
        }
        jdbc.update("UPDATE extension_plugins SET lifecycle_state='ENABLED',updated_at=now() WHERE plugin_id=?", pluginId);
        refreshProvisioning(pluginId);
        audit(pluginId, actor, "ENABLE", Map.of("image", row.manifest().image()));
        return ResponseEntity.ok(Map.of("pluginId", pluginId, "lifecycleState", "ENABLED"));
    }

    @PostMapping("/{pluginId}/credentials/rotate")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ResponseEntity<?> rotateCredentials(@PathVariable String pluginId, JwtAuthenticationToken actor) {
        PluginRow row = findPlugin(pluginId);
        ExtensionRuntimePort runtime = runtime();
        boolean wasEnabled;
        String restoreState;
        if ("ROTATING".equals(row.lifecycleState())) {
            wasEnabled = row.rotationWasEnabled();
            restoreState = row.rotationPreviousState();
        } else if (Set.of("INSTALLED", "DISABLED", "ENABLED").contains(row.lifecycleState())) {
            wasEnabled = "ENABLED".equals(row.lifecycleState());
            restoreState = row.lifecycleState();
            int claimed = jdbc.update("UPDATE extension_plugins SET rotation_was_enabled=?,rotation_previous_state=?,lifecycle_state='ROTATING',updated_at=now() WHERE plugin_id=? AND lifecycle_state=?",
                    wasEnabled, restoreState, pluginId, row.lifecycleState());
            if (claimed != 1) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Plugin lifecycle changed; retry credential rotation"));
        } else {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Plugin must be installed and stable before credential rotation"));
        }
        int generation = jdbc.queryForObject("UPDATE extension_plugins SET credential_generation=credential_generation+1,updated_at=now() WHERE plugin_id=? AND lifecycle_state='ROTATING' RETURNING credential_generation",
                Integer.class, pluginId);
        try {
            runtime.rotateCredentials(row.manifest(), generation, wasEnabled, runtimeConfiguration(row));
        } catch (RuntimeException failure) {
            jdbc.update("UPDATE extension_plugins SET lifecycle_state='FAILED',rotation_was_enabled=false,rotation_previous_state=NULL,updated_at=now() WHERE plugin_id=? AND lifecycle_state='ROTATING'", pluginId);
            refreshProvisioning(pluginId);
            audit(pluginId, actor, "CREDENTIAL_ROTATION_FAILED", Map.of("errorClass", failure.getClass().getSimpleName(), "generation", generation));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "Extension credentials could not be rotated"));
        }
        String state = restoreState == null ? (wasEnabled ? "ENABLED" : "DISABLED") : restoreState;
        jdbc.update("UPDATE extension_plugins SET lifecycle_state=?,rotation_was_enabled=false,rotation_previous_state=NULL,updated_at=now() WHERE plugin_id=? AND lifecycle_state='ROTATING'", state, pluginId);
        refreshProvisioning(pluginId);
        audit(pluginId, actor, "CREDENTIAL_ROTATED", Map.of("generation", generation));
        return ResponseEntity.ok(Map.of("pluginId", pluginId, "lifecycleState", state, "credentialGeneration", generation));
    }

    @PostMapping("/{pluginId}/update")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ResponseEntity<?> update(@PathVariable String pluginId, @RequestBody PluginManifest replacement,
                                    JwtAuthenticationToken actor) {
        PluginRow current = findPlugin(pluginId);
        if (!Set.of("INSTALLED", "DISABLED").contains(current.lifecycleState())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Disable the plugin before updating it"));
        }
        if (!pluginId.equals(replacement.id())) return ResponseEntity.badRequest().body(Map.of("message", "An update must retain the registered plugin id"));
        if (compareVersions(replacement.version(), current.manifest().version()) <= 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "Update version must be greater than the installed version"));
        }
        if (existsRelease(pluginId, replacement.version())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Plugin release versions are immutable and cannot be reused"));
        }
        ManifestValidationResponse validation = validateManifest(replacement, registeredPluginIds());
        List<String> errors = new java.util.ArrayList<>(validation.errors());
        errors.addAll(dependencyErrors(replacement, false));
        var configuration = configurationValidator.validate(replacement, current.publicConfiguration(), current.encryptedSecrets().keySet());
        errors.addAll(configuration.errors());
        if (!errors.isEmpty()) return ResponseEntity.badRequest().body(new ManifestValidationResponse(false, coreVersion, errors));
        ExtensionRuntimePort runtime = runtime();
        try {
            runtime.update(replacement);
        } catch (RuntimeException failure) {
            audit(pluginId, actor, "UPDATE_FAILED", Map.of("errorClass", failure.getClass().getSimpleName(), "version", replacement.version()));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "Extension image could not be updated"));
        }
        jdbc.update("INSERT INTO extension_plugin_releases(plugin_id,version,image_reference,manifest) VALUES (?,?,?,?::jsonb) ON CONFLICT (plugin_id,version) DO NOTHING",
                pluginId, current.manifest().version(), current.manifest().image(), write(current.manifest()));
        Map<String, String> retainedSecrets = retainedSecrets(replacement, current.encryptedSecrets());
        jdbc.update("UPDATE extension_plugins SET manifest=?::jsonb,image_reference=?,public_configuration=?::jsonb,encrypted_secrets=?::jsonb,configuration_version=configuration_version+1,lifecycle_state='INSTALLED',updated_at=now() WHERE plugin_id=?",
                write(replacement), replacement.image(), write(configuration.publicValues()), write(retainedSecrets), pluginId);
        refreshProvisioning(pluginId);
        audit(pluginId, actor, "UPDATE", Map.of("fromVersion", current.manifest().version(), "toVersion", replacement.version()));
        return ResponseEntity.ok(new PluginView(replacement, "INSTALLED", configuration.publicValues(),
                Set.copyOf(retainedSecrets.keySet()), current.configurationVersion() + 1));
    }

    @PostMapping("/{pluginId}/rollback")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ResponseEntity<?> rollback(@PathVariable String pluginId, JwtAuthenticationToken actor) {
        PluginRow current = findPlugin(pluginId);
        if (!Set.of("INSTALLED", "DISABLED").contains(current.lifecycleState())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Disable the plugin before rollback"));
        }
        PluginManifest previous = jdbc.query("SELECT manifest FROM extension_plugin_releases WHERE plugin_id=? AND version<>? ORDER BY release_id DESC LIMIT 1",
                (rs, row) -> read(rs.getString("manifest"), PluginManifest.class), pluginId, current.manifest().version())
                .stream().findFirst().orElse(null);
        if (previous == null) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "No earlier plugin release is available"));
        var configuration = configurationValidator.validate(previous, current.publicConfiguration(), current.encryptedSecrets().keySet());
        if (!configuration.valid()) return ResponseEntity.status(HttpStatus.CONFLICT).body(new ConfigurationErrorResponse(configuration.errors()));
        ExtensionRuntimePort runtime = runtime();
        try {
            runtime.update(previous);
        } catch (RuntimeException failure) {
            audit(pluginId, actor, "ROLLBACK_FAILED", Map.of("errorClass", failure.getClass().getSimpleName(), "version", previous.version()));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "Previous extension image could not be restored"));
        }
        jdbc.update("INSERT INTO extension_plugin_releases(plugin_id,version,image_reference,manifest) VALUES (?,?,?,?::jsonb) ON CONFLICT (plugin_id,version) DO NOTHING",
                pluginId, current.manifest().version(), current.manifest().image(), write(current.manifest()));
        Map<String, String> retainedSecrets = retainedSecrets(previous, current.encryptedSecrets());
        jdbc.update("UPDATE extension_plugins SET manifest=?::jsonb,image_reference=?,public_configuration=?::jsonb,encrypted_secrets=?::jsonb,configuration_version=configuration_version+1,lifecycle_state='INSTALLED',updated_at=now() WHERE plugin_id=?",
                write(previous), previous.image(), write(configuration.publicValues()), write(retainedSecrets), pluginId);
        refreshProvisioning(pluginId);
        audit(pluginId, actor, "ROLLBACK", Map.of("fromVersion", current.manifest().version(), "toVersion", previous.version()));
        return ResponseEntity.ok(new PluginView(previous, "INSTALLED", configuration.publicValues(),
                Set.copyOf(retainedSecrets.keySet()), current.configurationVersion() + 1));
    }

    @PostMapping("/{pluginId}/disable")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ResponseEntity<?> disable(@PathVariable String pluginId, JwtAuthenticationToken actor) {
        PluginRow row = findPlugin(pluginId);
        if (!Set.of("ENABLED", "INSTALLED", "DISABLED", "FAILED").contains(row.lifecycleState())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Plugin is not installed"));
        }
        List<PluginManifest> enabled = jdbc.query("SELECT manifest FROM extension_plugins WHERE lifecycle_state='ENABLED' AND plugin_id<>?",
                (rs, rowNumber) -> read(rs.getString("manifest"), PluginManifest.class), pluginId);
        List<String> dependents = PluginDependencyPolicy.enabledDependents(pluginId, enabled);
        if (!dependents.isEmpty()) return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "Enabled plugins depend on this plugin", "dependents", dependents));
        ExtensionRuntimePort runtime = runtime();
        try {
            runtime.disable(pluginId);
        } catch (RuntimeException failure) {
            jdbc.update("UPDATE extension_plugins SET lifecycle_state='FAILED',updated_at=now() WHERE plugin_id=?", pluginId);
            refreshProvisioning(pluginId);
            audit(pluginId, actor, "DISABLE_FAILED", Map.of("errorClass", failure.getClass().getSimpleName()));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "Extension could not be stopped"));
        }
        jdbc.update("UPDATE extension_plugins SET lifecycle_state='DISABLED',updated_at=now() WHERE plugin_id=?", pluginId);
        refreshProvisioning(pluginId);
        audit(pluginId, actor, "DISABLE", Map.of());
        return ResponseEntity.ok(Map.of("pluginId", pluginId, "lifecycleState", "DISABLED"));
    }

    @DeleteMapping("/{pluginId}")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ResponseEntity<?> uninstall(@PathVariable String pluginId, JwtAuthenticationToken actor) {
        PluginRow row = findPlugin(pluginId);
        if ("UNINSTALLED".equals(row.lifecycleState())) {
            return ResponseEntity.ok(Map.of("pluginId", pluginId, "lifecycleState", "UNINSTALLED"));
        }
        if ("ENABLED".equals(row.lifecycleState())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Disable the plugin before uninstalling it"));
        }
        List<PluginManifest> enabled = jdbc.query("SELECT manifest FROM extension_plugins WHERE lifecycle_state='ENABLED' AND plugin_id<>?",
                (rs, rowNumber) -> read(rs.getString("manifest"), PluginManifest.class), pluginId);
        List<String> dependents = PluginDependencyPolicy.enabledDependents(pluginId, enabled);
        if (!dependents.isEmpty()) return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "Enabled plugins depend on this plugin", "dependents", dependents));
        if (Set.of("INSTALLED", "DISABLED", "FAILED", "UNHEALTHY").contains(row.lifecycleState())) {
            try {
                runtime().uninstall(pluginId);
            } catch (RuntimeException failure) {
                audit(pluginId, actor, "UNINSTALL_FAILED", Map.of("errorClass", failure.getClass().getSimpleName()));
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "Plugin runtime could not be removed"));
            }
        }
        jdbc.update("UPDATE extension_plugins SET public_configuration='{}'::jsonb,encrypted_secrets='{}'::jsonb,lifecycle_state='UNINSTALLED',updated_at=now() WHERE plugin_id=?",
                pluginId);
        refreshProvisioning(pluginId);
        audit(pluginId, actor, "UNINSTALL", Map.of("configurationPurged", true));
        return ResponseEntity.ok(Map.of("pluginId", pluginId, "lifecycleState", "UNINSTALLED"));
    }

    @PostMapping("/{pluginId}/restore")
    public ResponseEntity<?> restoreRegistration(@PathVariable String pluginId, JwtAuthenticationToken actor) {
        PluginRow row = findPlugin(pluginId);
        if (!"UNINSTALLED".equals(row.lifecycleState())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Only an uninstalled plugin can be restored"));
        }
        List<String> errors = manifestValidator.validate(row.manifest(), coreVersion, registeredPluginIds());
        if (!errors.isEmpty()) return ResponseEntity.badRequest().body(new ManifestValidationResponse(false, coreVersion, errors));
        jdbc.update("UPDATE extension_plugins SET public_configuration='{}'::jsonb,encrypted_secrets='{}'::jsonb,lifecycle_state='VALIDATED',updated_at=now() WHERE plugin_id=?",
                pluginId);
        refreshProvisioning(pluginId);
        audit(pluginId, actor, "RESTORE_REGISTRATION", Map.of("version", row.manifest().version(), "configurationPurged", true));
        return ResponseEntity.ok(new PluginView(row.manifest(), "VALIDATED", Map.of(), Set.of(), 0));
    }

    @GetMapping("/{pluginId}/health")
    @Transactional(readOnly = true, propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ExtensionRuntimePort.RuntimeHealth health(@PathVariable String pluginId) {
        PluginRow row = findPlugin(pluginId);
        return runtime().health(row.manifest());
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
                    "{}", write(request.payload()), java.time.Duration.ofMillis(request.deadlineMillis()));
        } catch (IllegalArgumentException invalidRequest) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, invalidRequest.getMessage());
        }
        if (!row.manifest().extensionPoints().contains(request.extensionPoint())) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Extension point is not declared by this plugin");
        }
        String payload = write(request.payload());
        Map<String, Object> providerConfiguration = new HashMap<>(row.publicConfiguration());
        row.encryptedSecrets().forEach((key, ciphertext) -> providerConfiguration.put(key, encryption.decrypt(ciphertext)));
        String configuration = write(providerConfiguration);
        try {
            var result = runtime().invoke(pluginId, row.credentialGeneration(), request.extensionPoint(), request.operation(), request.idempotencyKey(),
                    configuration, payload, java.time.Duration.ofMillis(request.deadlineMillis()));
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
        try {
            validateBoundConfigurationSize(row.manifest(), validation.publicValues(), validation.newSecrets(), updatedSecrets);
        } catch (IllegalArgumentException tooLarge) {
            return ResponseEntity.badRequest().body(new ConfigurationErrorResponse(List.of(tooLarge.getMessage())));
        }
        String publicJson = write(validation.publicValues());
        String secretJson = write(updatedSecrets);
        jdbc.update("UPDATE extension_plugins SET public_configuration=?::jsonb,encrypted_secrets=?::jsonb,lifecycle_state='CONFIGURED',configuration_version=configuration_version+1,updated_at=now() WHERE plugin_id=?",
                publicJson, secretJson, pluginId);
        refreshProvisioning(pluginId);
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
        return jdbc.query("SELECT actor_user_id,action,details,occurred_at FROM extension_audit_events WHERE plugin_id=? ORDER BY occurred_at DESC LIMIT 100",
                (rs, row) -> new AuditView(rs.getObject("actor_user_id", UUID.class), rs.getString("action"),
                        read(rs.getString("details"), OBJECT_MAP), rs.getTimestamp("occurred_at").toInstant()), pluginId);
    }

    private ManifestValidationResponse validateManifest(PluginManifest manifest, Set<String> installed) {
        List<String> errors = manifestValidator.validate(manifest, coreVersion, installed);
        return new ManifestValidationResponse(errors.isEmpty(), coreVersion, errors);
    }

    private void refreshProvisioning(String pluginId) { provisioning.refreshPlugin(pluginId); }

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

    private boolean existsRelease(String pluginId, String version) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM extension_plugin_releases WHERE plugin_id=? AND version=?)", Boolean.class, pluginId, version));
    }

    private PluginRow findPlugin(String id) {
        return jdbc.query("SELECT manifest,public_configuration,encrypted_secrets,lifecycle_state,configuration_version,credential_generation,rotation_was_enabled,rotation_previous_state FROM extension_plugins WHERE plugin_id=?",
                (rs, row) -> new PluginRow(read(rs.getString("manifest"), PluginManifest.class),
                        read(rs.getString("public_configuration"), OBJECT_MAP), read(rs.getString("encrypted_secrets"), STRING_MAP),
                        rs.getString("lifecycle_state"), rs.getInt("configuration_version"), rs.getInt("credential_generation"), rs.getBoolean("rotation_was_enabled"), rs.getString("rotation_previous_state")), id)
                .stream().findFirst().orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Plugin is not registered"));
    }

    private PluginView view(PluginRow row) {
        return new PluginView(row.manifest(), row.lifecycleState(), row.publicConfiguration(),
                Set.copyOf(row.encryptedSecrets().keySet()), row.configurationVersion());
    }

    private Map<String, String> runtimeConfiguration(PluginRow row) {
        Map<String, String> values = new HashMap<>();
        Set<String> boundKeys = boundConfigurationKeys(row.manifest());
        row.publicConfiguration().forEach((key, value) -> {
            if (boundKeys.contains(key) && value != null) values.put(key, value instanceof String text ? text : String.valueOf(value));
        });
        row.encryptedSecrets().forEach((key, ciphertext) -> {
            if (boundKeys.contains(key)) values.put(key, encryption.decrypt(ciphertext));
        });
        long encodedBytes = values.entrySet().stream().mapToLong(entry -> entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum();
        if (values.size() > 100 || encodedBytes > 1_048_576) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Plugin configuration is too large for its runtime");
        }
        return Map.copyOf(values);
    }

    private void validateBoundConfigurationSize(PluginManifest manifest, Map<String, Object> publicValues,
            Map<String, String> newSecrets, Map<String, String> encryptedSecrets) {
        Map<String, Object> values = new HashMap<>(publicValues);
        values.putAll(newSecrets);
        Set<String> boundKeys = boundConfigurationKeys(manifest);
        encryptedSecrets.forEach((key, ciphertext) -> {
            if (boundKeys.contains(key)) values.computeIfAbsent(key, ignored -> encryption.decrypt(ciphertext));
        });
        long bytes = java.util.stream.Stream.concat(manifest.configurationBindings().entrySet().stream(),
                manifest.services().stream().flatMap(service -> service.configurationBindings().entrySet().stream()))
                .mapToLong(binding -> {
                    Object value = values.get(binding.getValue());
                    return value == null ? 0 : binding.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                            + value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                }).sum();
        if (bytes > 900_000) throw new IllegalArgumentException("Bound runtime configuration exceeds 900 KiB");
    }

    private static Set<String> boundConfigurationKeys(PluginManifest manifest) {
        Set<String> keys = new java.util.HashSet<>(manifest.configurationBindings().values());
        manifest.services().forEach(service -> keys.addAll(service.configurationBindings().values()));
        return Set.copyOf(keys);
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

    private static int compareVersions(String left, String right) {
        String[] l = left.split("[-+]")[0].split("\\."), r = right.split("[-+]")[0].split("\\.");
        for (int i = 0; i < 3; i++) {
            int compared = Integer.compare(Integer.parseInt(l[i]), Integer.parseInt(r[i]));
            if (compared != 0) return compared;
        }
        return left.compareTo(right);
    }

    private static Map<String, String> retainedSecrets(PluginManifest manifest, Map<String, String> current) {
        Map<String, String> retained = new HashMap<>();
        current.forEach((key, ciphertext) -> {
            var field = manifest.configuration().get(key);
            if (field != null && "secret".equals(field.type())) retained.put(key, ciphertext);
        });
        return Map.copyOf(retained);
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
                             Map<String, String> encryptedSecrets, String lifecycleState, int configurationVersion,
                             int credentialGeneration, boolean rotationWasEnabled, String rotationPreviousState) { }
    public record ManifestValidationResponse(boolean valid, String coreVersion, List<String> errors) {
        public ManifestValidationResponse { errors = List.copyOf(errors); }
    }
    public record PluginSummary(String id, String name, String version, String lifecycleState,
                                int configurationVersion, Set<String> configuredSecrets,
                                PluginManifest.GeographicAvailability geographicAvailability) { }
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

