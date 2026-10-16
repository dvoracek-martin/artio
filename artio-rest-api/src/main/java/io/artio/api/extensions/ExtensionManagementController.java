package io.artio.api.extensions;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.security.EncryptionService;
import io.artio.api.distributions.DistributionPluginProvisioningService;
import io.artio.extensions.PluginConfigurationValidator;
import io.artio.extensions.PluginManifest;
import io.artio.extensions.PluginManifestV2;
import io.artio.extensions.CapabilityRegistry;
import io.artio.extensions.CapabilityRouter;
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
@RequestMapping("/api/v1/admin/extensions")
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
    private final MarketplaceCatalogClient marketplace;
    private final DistributionPluginProvisioningService provisioning;
    private final PluginEventDeliveryOperations eventDeliveryOperations;
    private final PluginEventBackfillOperations eventBackfillOperations;

    public ExtensionManagementController(PluginManifestValidator manifestValidator,
            PluginConfigurationValidator configurationValidator, EncryptionService encryption,
            JdbcTemplate jdbc, ObjectMapper mapper,
            @Value("${artio.extensions.core-version:0.1.0}") String coreVersion,
            ObjectProvider<ExtensionRuntimePort> dockerRuntime, DistributionPluginProvisioningService provisioning,
            MarketplaceCatalogClient marketplace,
            PluginEventDeliveryOperations eventDeliveryOperations, PluginEventBackfillOperations eventBackfillOperations) {
        this.manifestValidator = manifestValidator;
        this.configurationValidator = configurationValidator;
        this.encryption = encryption;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.coreVersion = coreVersion;
        this.dockerRuntime = dockerRuntime;
        this.marketplace = marketplace;
        this.provisioning = provisioning;
        this.eventDeliveryOperations = eventDeliveryOperations;
        this.eventBackfillOperations = eventBackfillOperations;
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
        List<PluginSummary> plugins = jdbc.query("SELECT p.plugin_id,p.manifest,p.lifecycle_state,p.configuration_version,p.encrypted_secrets,"
                        + "(SELECT count(*) FROM extension_plugin_instances i WHERE i.plugin_id=p.plugin_id AND i.lifecycle_state<>'UNINSTALLED') AS instance_count,"
                        + "(SELECT count(*) FROM extension_capability_bindings b JOIN extension_plugin_instances i ON i.instance_id=b.instance_id WHERE i.plugin_id=p.plugin_id) AS binding_count,"
                        + "(SELECT count(*) FROM extension_event_subscriptions s JOIN extension_plugin_instances i ON i.instance_id=s.instance_id WHERE i.plugin_id=p.plugin_id) AS subscription_count,"
                        + "COALESCE((SELECT string_agg(DISTINCT i.compatibility_mode,',') FROM extension_plugin_instances i WHERE i.plugin_id=p.plugin_id),'LEGACY_V1') AS compatibility_modes "
                        + "FROM extension_plugins p WHERE p.lifecycle_state<>'UNINSTALLED' ORDER BY p.plugin_id",
                (rs, row) -> {
                    PluginManifest manifest = read(rs.getString("manifest"), PluginManifest.class);
                    Map<String, String> secrets = read(rs.getString("encrypted_secrets"), STRING_MAP);
                    return new PluginSummary(manifest.id(), manifest.name(), manifest.version(), rs.getString("lifecycle_state"),
                            rs.getInt("configuration_version"), Set.copyOf(secrets.keySet()), manifest.geographicAvailability(),
                            rs.getString("compatibility_modes"), rs.getLong("instance_count"), rs.getLong("binding_count"), rs.getLong("subscription_count"));
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

    @GetMapping("/marketplace/plugins")
    @Transactional(readOnly = true, propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Object marketplacePlugins(@RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String country,
            @RequestParam(defaultValue = "false") boolean includePrerelease,
            @RequestParam(defaultValue = "en") String locale,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be nonnegative and size must be between 1 and 100");
        if (country != null && !country.matches("[A-Z]{2}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "country must be an ISO 3166-1 alpha-2 code");
        return marketplace.plugins(q, category, country, coreVersion, includePrerelease, locale, page, size);
    }

    @GetMapping("/marketplace/plugins/{pluginId}/releases/{version}/manifest")
    @Transactional(readOnly = true, propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Object marketplaceManifest(@PathVariable String pluginId, @PathVariable String version,
            @RequestParam(required = false) String country,
            @RequestParam(defaultValue = "false") boolean includePrerelease) {
        if (country != null && !country.matches("[A-Z]{2}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "country must be an ISO 3166-1 alpha-2 code");
        return marketplace.releaseManifest(pluginId, version, coreVersion, country, includePrerelease);
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
        Long releaseId = jdbc.queryForObject("SELECT release_id FROM extension_plugin_releases WHERE plugin_id=? AND version=?",
                Long.class, manifest.id(), manifest.version());
        jdbc.update("INSERT INTO extension_plugin_instances(instance_id,plugin_id,instance_key,release_id,compatibility_mode,lifecycle_state,runtime_identity) VALUES (?,?, 'legacy-default',?,'LEGACY_V1','CONFIGURED',?)",
                UUID.randomUUID(), manifest.id(), releaseId, manifest.id());
        refreshProvisioning(manifest.id());
        audit(manifest.id(), actor, "REGISTER", Map.of("version", manifest.version(), "image", manifest.image()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new PluginView(manifest, "VALIDATED", Map.of(), Set.of(), 0));
    }

    @PostMapping("/register-v2")
    public ResponseEntity<?> registerV2(@RequestBody PluginManifestV2 manifest, JwtAuthenticationToken actor) {
        if (manifest == null || manifest.packageManifest() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "v2 package manifest is required"));
        }
        PluginManifest source = manifest.packageManifest();
        if (exists(source.id())) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Plugin is already registered"));
        List<String> errors = manifestValidator.validate(manifest, coreVersion, registeredPluginIds());
        if (!errors.isEmpty()) return ResponseEntity.badRequest().body(new ManifestValidationResponse(false, coreVersion, errors));
        PluginManifest runtimeManifest = asRuntimeManifest(source);
        jdbc.update("INSERT INTO extension_plugins(plugin_id,manifest,image_reference,lifecycle_state) VALUES (?,?::jsonb,?,'VALIDATED')",
                source.id(), write(runtimeManifest), source.image());
        jdbc.update("INSERT INTO extension_plugin_releases(plugin_id,version,image_reference,manifest) VALUES (?,?,?,?::jsonb)",
                source.id(), source.version(), source.image(), write(runtimeManifest));
        Long releaseId = jdbc.queryForObject("SELECT release_id FROM extension_plugin_releases WHERE plugin_id=? AND version=?",
                Long.class, source.id(), source.version());
        jdbc.update("INSERT INTO extension_plugin_v2_releases(release_id,plugin_id,manifest) VALUES (?,?,?::jsonb)",
                releaseId, source.id(), write(manifest));
        UUID instanceId = UUID.randomUUID();
        jdbc.update("INSERT INTO extension_plugin_instances(instance_id,plugin_id,instance_key,release_id,compatibility_mode,lifecycle_state,runtime_identity) VALUES (?,?, 'default',?,'CAPABILITY_V2','CONFIGURED',?)",
                instanceId, source.id(), releaseId, source.id());
        for (PluginManifestV2.EventSubscription subscription : manifest.subscriptions()) {
            jdbc.update("INSERT INTO extension_event_subscriptions(subscription_id,instance_id,event_type,schema_version,permissions_snapshot) VALUES (?,?,?,?,?::jsonb)",
                    UUID.randomUUID(), instanceId, subscription.eventType(), subscription.schemaVersion(), write(subscription.permissions()));
        }
        audit(source.id(), actor, "REGISTER_V2", Map.of("version", source.version(), "image", source.image()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("pluginId", source.id(), "compatibilityMode", "CAPABILITY_V2",
                "lifecycleState", "VALIDATED", "releaseId", releaseId));
    }

    @GetMapping("/{pluginId}/bindings")
    @Transactional(readOnly = true)
    public List<BindingView> bindings(@PathVariable String pluginId) {
        findPlugin(pluginId);
        return jdbc.query("SELECT b.binding_id,b.instance_id,b.capability_id,b.operation_name,b.scope,b.priority,b.eligibility_state "
                        + "FROM extension_capability_bindings b JOIN extension_plugin_instances i ON i.instance_id=b.instance_id "
                        + "WHERE i.plugin_id=? ORDER BY b.capability_id,b.operation_name,b.binding_id",
                (rs, row) -> new BindingView(rs.getObject("binding_id", UUID.class), rs.getObject("instance_id", UUID.class),
                        rs.getString("capability_id"), rs.getString("operation_name"), read(rs.getString("scope"), STRING_MAP),
                        rs.getInt("priority"), rs.getString("eligibility_state")), pluginId);
    }

    @PutMapping("/{pluginId}/bindings/{bindingId}/state")
    public ResponseEntity<?> updateBindingState(@PathVariable String pluginId, @PathVariable UUID bindingId,
            @RequestBody BindingStateRequest request, JwtAuthenticationToken actor) {
        findPlugin(pluginId);
        if (request == null || !Set.of("ACTIVE", "DRAINING", "DISABLED").contains(request.state()))
            return ResponseEntity.badRequest().body(Map.of("message", "state must be ACTIVE, DRAINING or DISABLED"));
        int updated = jdbc.update("UPDATE extension_capability_bindings b SET eligibility_state=?,updated_at=now() "
                        + "FROM extension_plugin_instances i WHERE b.instance_id=i.instance_id AND i.plugin_id=? AND b.binding_id=?",
                request.state(), pluginId, bindingId);
        if (updated == 0) return ResponseEntity.notFound().build();
        audit(pluginId, actor, "UPDATE_CAPABILITY_BINDING", Map.of("bindingId", bindingId.toString(), "state", request.state()));
        return ResponseEntity.ok(Map.of("bindingId", bindingId, "state", request.state()));
    }

    @GetMapping("/{pluginId}/subscriptions")
    @Transactional(readOnly = true)
    public List<SubscriptionView> subscriptions(@PathVariable String pluginId) {
        findPlugin(pluginId);
        return jdbc.query("SELECT s.subscription_id,s.instance_id,s.event_type,s.schema_version,s.subscription_state "
                        + "FROM extension_event_subscriptions s JOIN extension_plugin_instances i ON i.instance_id=s.instance_id "
                        + "WHERE i.plugin_id=? ORDER BY s.event_type,s.schema_version",
                (rs, row) -> new SubscriptionView(rs.getObject("subscription_id", UUID.class), rs.getObject("instance_id", UUID.class),
                        rs.getString("event_type"), rs.getInt("schema_version"), rs.getString("subscription_state")), pluginId);
    }

    @PutMapping("/{pluginId}/subscriptions/{subscriptionId}/state")
    public ResponseEntity<?> updateSubscriptionState(@PathVariable String pluginId, @PathVariable UUID subscriptionId,
            @RequestBody SubscriptionStateRequest request, JwtAuthenticationToken actor) {
        findPlugin(pluginId);
        if (request == null || !Set.of("ACTIVE", "PAUSED", "DRAINING", "DISABLED").contains(request.state())) {
            return ResponseEntity.badRequest().body(Map.of("message", "state must be ACTIVE, PAUSED, DRAINING or DISABLED"));
        }
        int updated = jdbc.update("UPDATE extension_event_subscriptions s SET subscription_state=?,updated_at=now() "
                        + "FROM extension_plugin_instances i WHERE s.instance_id=i.instance_id AND i.plugin_id=? AND s.subscription_id=?",
                request.state(), pluginId, subscriptionId);
        if (updated == 0) return ResponseEntity.notFound().build();
        audit(pluginId, actor, "UPDATE_EVENT_SUBSCRIPTION", Map.of("subscriptionId", subscriptionId.toString(), "state", request.state()));
        return ResponseEntity.ok(Map.of("subscriptionId", subscriptionId, "state", request.state()));
    }

    @GetMapping("/{pluginId}/instances")
    @Transactional(readOnly = true)
    public List<PluginInstanceView> instances(@PathVariable String pluginId) {
        findPlugin(pluginId);
        return jdbc.query("SELECT instance_id,instance_key,release_id,compatibility_mode,lifecycle_state,configuration_generation,"
                        + "runtime_identity,(SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(public_configuration) AS keys(k)) AS public_keys,"
                        + "(SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(encrypted_secrets) AS keys(k)) AS secret_keys "
                        + "FROM extension_plugin_instances WHERE plugin_id=? ORDER BY created_at,instance_key",
                (rs, row) -> new PluginInstanceView(rs.getObject("instance_id", UUID.class), rs.getString("instance_key"),
                        rs.getLong("release_id"), rs.getString("compatibility_mode"), rs.getString("lifecycle_state"),
                        rs.getInt("configuration_generation"), rs.getString("runtime_identity"),
                        arraySet(rs.getArray("public_keys")), arraySet(rs.getArray("secret_keys"))), pluginId);
    }

    @GetMapping("/{pluginId}/instances/{instanceId}/health")
    @Transactional(readOnly = true, propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ExtensionRuntimePort.RuntimeHealth instanceHealth(@PathVariable String pluginId, @PathVariable UUID instanceId) {
        InstanceRow instance = findInstance(pluginId, instanceId);
        try {
            return runtime().healthInstance(instance.runtimeIdentity(), instance.manifest());
        } catch (RuntimeException unavailable) {
            return new ExtensionRuntimePort.RuntimeHealth(false, "UNAVAILABLE");
        }
    }

    @GetMapping("/{pluginId}/instances/{instanceId}/obligations")
    @Transactional(readOnly = true)
    public InstanceObligationsView instanceObligations(@PathVariable String pluginId, @PathVariable UUID instanceId) {
        findInstance(pluginId, instanceId);
        return jdbc.query("""
                SELECT
                  (SELECT count(*) FROM extension_operation_attempts WHERE instance_id=? AND attempt_state IN ('PENDING','IN_PROGRESS','UNKNOWN','RECONCILING')) AS operations,
                  (SELECT count(*) FROM extension_event_deliveries WHERE instance_id=? AND state<>'DELIVERED') AS deliveries,
                  (SELECT count(*) FROM catalog_media_storage_objects WHERE object_state IN ('ACTIVE','DELETE_PENDING')
                    AND (primary_instance_id=? OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(replica_instance_ids) r(instance_id) WHERE r.instance_id=?))) AS media,
                  (SELECT count(*) FROM payments WHERE provider_instance_id=? AND status='PENDING') AS payments,
                  (SELECT count(*) FROM order_shipments s JOIN orders o ON o.id=s.order_id WHERE s.provider_instance_id=?
                    AND o.status IN ('CONFIRMED','PAID','PARTIALLY_SHIPPED','SHIPPED')) AS shipments,
                  (SELECT count(*) FROM payment_refunds r JOIN payments p ON p.id=r.payment_id
                    WHERE p.provider_instance_id=? AND r.status='PROCESSING') AS refunds
                """, rs -> {
            if (!rs.next()) throw new IllegalStateException("Instance obligation counts were not returned");
            long operations = rs.getLong("operations");
            long deliveries = rs.getLong("deliveries");
            long media = rs.getLong("media");
            long payments = rs.getLong("payments");
            long shipments = rs.getLong("shipments");
            long refunds = rs.getLong("refunds");
            return new InstanceObligationsView(instanceId, operations, deliveries, media, payments, shipments, refunds,
                    operations + deliveries + media + payments + shipments + refunds);
        }, instanceId, instanceId, instanceId, instanceId.toString(), instanceId, instanceId, instanceId);
    }

    @PostMapping("/{pluginId}/instances/{instanceId}/drain")
    public ResponseEntity<?> drainInstance(@PathVariable String pluginId, @PathVariable UUID instanceId, JwtAuthenticationToken actor) {
        findInstance(pluginId, instanceId);
        int updated = jdbc.update("UPDATE extension_plugin_instances SET lifecycle_state='DRAINING',updated_at=now() "
                + "WHERE plugin_id=? AND instance_id=? AND lifecycle_state IN ('ENABLED','UNHEALTHY')", pluginId, instanceId);
        if (updated == 0) {
            String state = jdbc.query("SELECT lifecycle_state FROM extension_plugin_instances WHERE plugin_id=? AND instance_id=?",
                    rs -> rs.next() ? rs.getString(1) : null, pluginId, instanceId);
            if (!"DRAINING".equals(state)) return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "Only an enabled or unhealthy instance can enter drain mode"));
        }
        jdbc.update("UPDATE extension_capability_bindings SET eligibility_state='DRAINING',updated_at=now() "
                + "WHERE instance_id=? AND eligibility_state='ACTIVE'", instanceId);
        jdbc.update("UPDATE extension_event_subscriptions SET subscription_state='DRAINING',updated_at=now() "
                + "WHERE instance_id=? AND subscription_state='ACTIVE'", instanceId);
        audit(pluginId, actor, "INSTANCE_DRAIN", Map.of("instanceId", instanceId.toString()));
        return ResponseEntity.ok(Map.of("instanceId", instanceId, "lifecycleState", "DRAINING",
                "message", "New routes and event subscriptions are stopped; existing obligations can still be reconciled"));
    }

    @PostMapping("/{pluginId}/instances")
    public ResponseEntity<?> createInstance(@PathVariable String pluginId, @RequestBody InstanceCreateRequest request,
            JwtAuthenticationToken actor) {
        PluginRow plugin = findPlugin(pluginId);
        if (request == null || request.instanceKey() == null
                || !request.instanceKey().matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            return ResponseEntity.badRequest().body(Map.of("message", "instanceKey must be a lowercase identifier of at most 64 characters"));
        }
        long releaseId;
        try {
            releaseId = jdbc.queryForObject("SELECT r.release_id FROM extension_plugin_releases r "
                            + "JOIN extension_plugin_v2_releases v ON v.release_id=r.release_id "
                            + "WHERE r.plugin_id=? AND r.version=?", Long.class, pluginId, plugin.manifest().version());
        } catch (org.springframework.dao.EmptyResultDataAccessException notV2) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Only a v2 package release can create isolated instances"));
        }
        var validation = configurationValidator.validate(plugin.manifest(), request.configuration(), Set.of());
        if (!validation.valid()) return ResponseEntity.badRequest().body(new ConfigurationErrorResponse(validation.errors()));
        String publicJson = write(validation.publicValues());
        Map<String, String> encryptedSecrets = encryptSecrets(validation.newSecrets());
        String secretJson = write(encryptedSecrets);
        try {
            validateBoundConfigurationSize(plugin.manifest(), validation.publicValues(), validation.newSecrets(), encryptedSecrets);
        } catch (IllegalArgumentException tooLarge) {
            return ResponseEntity.badRequest().body(new ConfigurationErrorResponse(List.of(tooLarge.getMessage())));
        }
        UUID instanceId = UUID.randomUUID();
        String runtimeIdentity = "instance-" + instanceId.toString().replace("-", "");
        try {
            jdbc.update("INSERT INTO extension_plugin_instances(instance_id,plugin_id,instance_key,release_id,compatibility_mode,"
                            + "lifecycle_state,runtime_identity,public_configuration,encrypted_secrets) "
                            + "VALUES (?,?,?,?,'CAPABILITY_V2','CONFIGURED',?,?::jsonb,?::jsonb)",
                    instanceId, pluginId, request.instanceKey(), releaseId, runtimeIdentity, publicJson, secretJson);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Instance key already exists for this package"));
        }
        PluginManifestV2 v2Manifest = jdbc.query("SELECT manifest FROM extension_plugin_v2_releases WHERE release_id=?",
                rs -> rs.next() ? read(rs.getString(1), PluginManifestV2.class) : null, releaseId);
        if (v2Manifest != null) {
            for (PluginManifestV2.EventSubscription subscription : v2Manifest.subscriptions()) {
                jdbc.update("INSERT INTO extension_event_subscriptions(subscription_id,instance_id,event_type,schema_version,permissions_snapshot) "
                                + "VALUES (?,?,?,?,?::jsonb)", UUID.randomUUID(), instanceId, subscription.eventType(),
                        subscription.schemaVersion(), write(subscription.permissions()));
            }
        }
        audit(pluginId, actor, "CREATE_INSTANCE", Map.of("instanceId", instanceId.toString(),
                "instanceKey", request.instanceKey(), "releaseId", releaseId));
        return ResponseEntity.status(HttpStatus.CREATED).body(instanceView(instanceId));
    }

    @PostMapping("/{pluginId}/instances/{instanceId}/install")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ResponseEntity<?> installInstance(@PathVariable String pluginId, @PathVariable UUID instanceId,
            JwtAuthenticationToken actor) {
        InstanceRow instance = findInstance(pluginId, instanceId);
        if (!Set.of("CONFIGURED", "INSTALLED", "DISABLED", "FAILED").contains(instance.lifecycleState())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Instance must be configured or stopped before installation"));
        }
        ExtensionRuntimePort runtime = runtime();
        try {
            runtime.installInstance(instance.runtimeIdentity(), instance.manifest());
            jdbc.update("UPDATE extension_plugin_instances SET lifecycle_state='INSTALLED',updated_at=now() WHERE instance_id=?", instanceId);
        } catch (RuntimeException failure) {
            jdbc.update("UPDATE extension_plugin_instances SET lifecycle_state='FAILED',updated_at=now() WHERE instance_id=?", instanceId);
            audit(pluginId, actor, "INSTANCE_INSTALL_FAILED", Map.of("instanceId", instanceId.toString(),
                    "errorClass", failure.getClass().getSimpleName()));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "Plugin instance image could not be installed"));
        }
        audit(pluginId, actor, "INSTANCE_INSTALL", Map.of("instanceId", instanceId.toString(), "releaseId", instance.releaseId()));
        return ResponseEntity.ok(Map.of("instanceId", instanceId, "lifecycleState", "INSTALLED"));
    }

    @PostMapping("/{pluginId}/instances/{instanceId}/enable")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ResponseEntity<?> enableInstance(@PathVariable String pluginId, @PathVariable UUID instanceId,
            JwtAuthenticationToken actor) {
        InstanceRow instance = findInstance(pluginId, instanceId);
        if (!Set.of("INSTALLED", "DISABLED").contains(instance.lifecycleState())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Instance must be installed and stopped before enabling"));
        }
        int generation = jdbc.queryForObject("SELECT credential_generation FROM extension_plugins WHERE plugin_id=?", Integer.class, pluginId);
        try {
            runtime().enableInstance(instance.runtimeIdentity(), instance.manifest(), generation,
                    runtimeConfiguration(instance.manifest(), instance.publicConfiguration(), instance.encryptedSecrets()));
            jdbc.update("UPDATE extension_plugin_instances SET lifecycle_state='ENABLED',updated_at=now() WHERE instance_id=?", instanceId);
        } catch (RuntimeException failure) {
            jdbc.update("UPDATE extension_plugin_instances SET lifecycle_state='FAILED',updated_at=now() WHERE instance_id=?", instanceId);
            audit(pluginId, actor, "INSTANCE_ENABLE_FAILED", Map.of("instanceId", instanceId.toString(),
                    "errorClass", failure.getClass().getSimpleName()));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "Plugin instance did not become available"));
        }
        audit(pluginId, actor, "INSTANCE_ENABLE", Map.of("instanceId", instanceId.toString()));
        return ResponseEntity.ok(Map.of("instanceId", instanceId, "lifecycleState", "ENABLED"));
    }

    @PostMapping("/{pluginId}/instances/{instanceId}/disable")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ResponseEntity<?> disableInstance(@PathVariable String pluginId, @PathVariable UUID instanceId,
            JwtAuthenticationToken actor) {
        InstanceRow instance = findInstance(pluginId, instanceId);
        if (!Set.of("ENABLED", "UNHEALTHY", "FAILED", "DRAINING").contains(instance.lifecycleState())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Instance is not enabled"));
        }
        runtime().disableInstance(instance.runtimeIdentity(), pluginId);
        jdbc.update("UPDATE extension_plugin_instances SET lifecycle_state='DISABLED',updated_at=now() WHERE instance_id=?", instanceId);
        audit(pluginId, actor, "INSTANCE_DISABLE", Map.of("instanceId", instanceId.toString()));
        return ResponseEntity.ok(Map.of("instanceId", instanceId, "lifecycleState", "DISABLED"));
    }

    @PutMapping("/{pluginId}/instances/{instanceId}/configuration")
    public ResponseEntity<?> configureInstance(@PathVariable String pluginId, @PathVariable UUID instanceId,
            @RequestBody Map<String, Object> submitted, JwtAuthenticationToken actor) {
        InstanceRow instance = findInstance(pluginId, instanceId);
        if ("ENABLED".equals(instance.lifecycleState())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Disable the instance before changing its configuration"));
        }
        var validation = configurationValidator.validate(instance.manifest(), submitted, instance.encryptedSecrets().keySet());
        if (!validation.valid()) return ResponseEntity.badRequest().body(new ConfigurationErrorResponse(validation.errors()));
        Map<String, String> encryptedSecrets = new HashMap<>(instance.encryptedSecrets());
        encryptedSecrets.putAll(encryptSecrets(validation.newSecrets()));
        try {
            validateBoundConfigurationSize(instance.manifest(), validation.publicValues(), validation.newSecrets(), encryptedSecrets);
        } catch (IllegalArgumentException tooLarge) {
            return ResponseEntity.badRequest().body(new ConfigurationErrorResponse(List.of(tooLarge.getMessage())));
        }
        int generation = jdbc.queryForObject("UPDATE extension_plugin_instances SET public_configuration=?::jsonb,encrypted_secrets=?::jsonb,"
                        + "configuration_generation=configuration_generation+1,lifecycle_state='CONFIGURED',updated_at=now() "
                        + "WHERE instance_id=? AND plugin_id=? AND lifecycle_state<>'ENABLED' RETURNING configuration_generation",
                Integer.class, write(validation.publicValues()), write(encryptedSecrets), instanceId, pluginId);
        audit(pluginId, actor, "CONFIGURE_INSTANCE", Map.of("instanceId", instanceId.toString(), "configurationGeneration", generation,
                "publicFields", validation.publicValues().keySet().stream().sorted().toList(),
                "configuredSecretFields", encryptedSecrets.keySet().stream().sorted().toList()));
        return ResponseEntity.ok(Map.of("instanceId", instanceId, "configurationGeneration", generation,
                "publicConfiguration", validation.publicValues(), "configuredSecrets", encryptedSecrets.keySet()));
    }

    @DeleteMapping("/{pluginId}/instances/{instanceId}")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ResponseEntity<?> uninstallInstance(@PathVariable String pluginId, @PathVariable UUID instanceId,
            JwtAuthenticationToken actor) {
        InstanceRow instance = findInstance(pluginId, instanceId);
        if (Set.of("ENABLED", "DRAINING", "UNHEALTHY").contains(instance.lifecycleState())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Disable or drain the instance before uninstalling it"));
        }
        boolean hasObligations = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS("
                + "SELECT 1 FROM extension_operation_attempts WHERE instance_id=? AND attempt_state IN ('PENDING','IN_PROGRESS','UNKNOWN','RECONCILING') "
                + "UNION ALL SELECT 1 FROM extension_event_deliveries WHERE instance_id=? AND state<>'DELIVERED' "
                + "UNION ALL SELECT 1 FROM catalog_media_storage_objects WHERE object_state IN ('ACTIVE','DELETE_PENDING') "
                + "AND (primary_instance_id=? OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(replica_instance_ids) r(instance_id) WHERE r.instance_id=?)) "
                + "UNION ALL SELECT 1 FROM payments WHERE provider_instance_id=? AND status='PENDING' "
                + "UNION ALL SELECT 1 FROM payment_refunds r JOIN payments p ON p.id=r.payment_id WHERE p.provider_instance_id=? AND r.status='PROCESSING' "
                + "UNION ALL SELECT 1 FROM order_shipments s JOIN orders o ON o.id=s.order_id WHERE s.provider_instance_id=? "
                + "AND o.status IN ('CONFIRMED','PAID','PARTIALLY_SHIPPED','SHIPPED'))", Boolean.class,
                instanceId, instanceId, instanceId, instanceId.toString(), instanceId, instanceId, instanceId));
        if (hasObligations) return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "Resolve outstanding operations, deliveries, media, payments, refunds and shipments before uninstalling this instance"));
        runtime().uninstallInstance(instance.runtimeIdentity(), pluginId);
        jdbc.update("UPDATE extension_capability_bindings SET eligibility_state='DISABLED',updated_at=now() WHERE instance_id=?", instanceId);
        jdbc.update("UPDATE extension_event_subscriptions SET subscription_state='DISABLED',updated_at=now() WHERE instance_id=?", instanceId);
        jdbc.update("UPDATE extension_plugin_instances SET public_configuration='{}'::jsonb,encrypted_secrets='{}'::jsonb,"
                + "lifecycle_state='UNINSTALLED',updated_at=now() WHERE instance_id=?", instanceId);
        audit(pluginId, actor, "UNINSTALL_INSTANCE", Map.of("instanceId", instanceId.toString(), "configurationPurged", true));
        return ResponseEntity.ok(Map.of("instanceId", instanceId, "lifecycleState", "UNINSTALLED"));
    }

    @GetMapping("/events/dead")
    @Transactional(readOnly = true)
    public List<DeadEventDeliveryView> deadEventDeliveries(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination");
        }
        return jdbc.query("SELECT delivery_id,plugin_id,event_type,attempt_count,last_error_code,created_at "
                        + "FROM extension_event_deliveries WHERE state='DEAD_LETTER' ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs, row) -> new DeadEventDeliveryView(rs.getObject("delivery_id", UUID.class), rs.getString("plugin_id"),
                        rs.getString("event_type"), rs.getInt("attempt_count"), rs.getString("last_error_code"),
                        rs.getTimestamp("created_at").toInstant()), size, (long) page * size);
    }

    @PostMapping("/events/{deliveryId}/replay")
    public ResponseEntity<?> replayEventDelivery(@PathVariable UUID deliveryId, JwtAuthenticationToken actor) {
        var result = eventDeliveryOperations.replay(deliveryId, UUID.fromString(actor.getToken().getSubject()));
        if (result == PluginEventDeliveryOperations.ReplayResult.NOT_DEAD_LETTER) return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "Event delivery is not in the dead-letter state"));
        if (result == PluginEventDeliveryOperations.ReplayResult.CHANGED) return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "Event delivery changed before replay"));
        return ResponseEntity.accepted().body(Map.of("deliveryId", deliveryId, "state", "PENDING"));
    }

    @PostMapping("/events/backfill/preview")
    public ResponseEntity<?> previewEventBackfill(@RequestBody BackfillPreviewRequest request, JwtAuthenticationToken actor) {
        if (request == null || request.subscriptionId() == null || request.from() == null || request.to() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "subscriptionId, from and to are required"));
        }
        try {
            var result = eventBackfillOperations.preview(request.subscriptionId(), request.from(), request.to(),
                    request.maxEvents(), request.ratePerMinute(), UUID.fromString(actor.getToken().getSubject()));
            return ResponseEntity.ok(result);
        } catch (PluginEventBackfillOperations.BackfillRejectedException invalid) {
            return ResponseEntity.unprocessableEntity().body(Map.of("message", invalid.getMessage()));
        }
    }

    @PostMapping("/events/backfill/{runId}/execute")
    public ResponseEntity<?> executeEventBackfill(@PathVariable UUID runId, JwtAuthenticationToken actor) {
        try {
            var result = eventBackfillOperations.execute(runId, UUID.fromString(actor.getToken().getSubject()));
            if ("EXPIRED".equals(result.state())) return ResponseEntity.status(HttpStatus.GONE).body(result);
            if ("PARTIAL_FAILURE".equals(result.state())) return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
            if ("COMPLETE".equals(result.state())) return ResponseEntity.ok(result);
            return ResponseEntity.accepted().body(result);
        } catch (PluginEventBackfillOperations.BackfillRejectedException invalid) {
            return ResponseEntity.unprocessableEntity().body(Map.of("message", invalid.getMessage()));
        }
    }

    @GetMapping("/events/backfill/{runId}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> eventBackfillStatus(@PathVariable UUID runId) {
        return jdbc.query("SELECT run_id,plugin_id,subscription_id,from_at,to_at,rate_per_minute,"
                        + "CASE WHEN status='PREVIEWED' AND expires_at<=now() THEN 'EXPIRED' ELSE status END AS status,created_at,"
                        + "executed_at,jsonb_array_length(event_ids) AS event_count FROM extension_event_backfill_runs WHERE run_id=?",
                rs -> rs.next() ? ResponseEntity.ok(new BackfillRunView(rs.getObject("run_id", UUID.class),
                        rs.getString("plugin_id"), rs.getObject("subscription_id", UUID.class),
                        rs.getTimestamp("from_at").toInstant(), rs.getTimestamp("to_at").toInstant(),
                        rs.getInt("rate_per_minute"), rs.getString("status"), rs.getInt("event_count"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("executed_at") == null ? null
                        : rs.getTimestamp("executed_at").toInstant())) : ResponseEntity.notFound().build(), runId);
    }

    @PostMapping("/{pluginId}/bindings")
    public ResponseEntity<?> createBinding(@PathVariable String pluginId, @RequestBody BindingRequest request,
                                           JwtAuthenticationToken actor) {
        PluginRow plugin = findPlugin(pluginId);
        if (request == null || request.capabilityId() == null || request.operationName() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "capabilityId and operationName are required"));
        }
        try {
            CapabilityRouter.validateBinding(request.capabilityId(), request.operationName(), request.scope());
        } catch (CapabilityRouter.RoutingException invalid) {
            return ResponseEntity.badRequest().body(Map.of("message", invalid.getMessage()));
        }
        UUID instanceId = request.instanceId() == null
                ? jdbc.query("SELECT instance_id FROM extension_plugin_instances WHERE plugin_id=? AND compatibility_mode='CAPABILITY_V2' AND instance_key='default'",
                        rs -> rs.next() ? rs.getObject(1, UUID.class) : null, pluginId)
                : jdbc.query("SELECT instance_id FROM extension_plugin_instances WHERE plugin_id=? AND compatibility_mode='CAPABILITY_V2' AND instance_id=?",
                        rs -> rs.next() ? rs.getObject(1, UUID.class) : null, pluginId, request.instanceId());
        if (instanceId == null) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Only v2 plugin instances may declare capability bindings"));
        Long releaseId = jdbc.queryForObject("SELECT release_id FROM extension_plugin_instances WHERE instance_id=?", Long.class, instanceId);
        PluginManifestV2 declared = jdbc.query("SELECT manifest FROM extension_plugin_v2_releases WHERE release_id=?",
                rs -> rs.next() ? read(rs.getString(1), PluginManifestV2.class) : null, releaseId);
        CapabilityRegistry.Operation operation = CapabilityRegistry.find(request.capabilityId()).orElseThrow()
                .operation(request.operationName()).orElseThrow();
        boolean authorized = declared != null && declared.capabilities().stream().anyMatch(capability ->
                capability.capabilityId().equals(request.capabilityId()) && capability.operations().contains(request.operationName())
                        && capability.permissions().contains(operation.requiredPermission()));
        if (!authorized) return ResponseEntity.badRequest().body(Map.of("message", "Binding operation must be declared and authorized by the current immutable release"));
        if (request.priority() < -10000 || request.priority() > 10000) {
            return ResponseEntity.badRequest().body(Map.of("message", "priority must be between -10000 and 10000"));
        }
        Map<String, String> scope = request.scope() == null ? Map.of() : request.scope();
        if (operation.policy() == CapabilityRegistry.Policy.EXCLUSIVE) {
            // Serialize owner edits for one operation; the overlap check and insert then share this transaction.
            jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?),hashtext(?))",
                    (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                    request.capabilityId(), request.operationName());
            List<Map.Entry<Map<String, String>, Integer>> activeBindings = jdbc.query(
                    "SELECT scope,priority FROM extension_capability_bindings WHERE capability_id=? AND operation_name=? AND eligibility_state='ACTIVE'",
                    (rs, row) -> Map.entry(read(rs.getString("scope"), STRING_MAP), rs.getInt("priority")),
                    request.capabilityId(), request.operationName());
            boolean ambiguous = activeBindings.stream().anyMatch(existing -> CapabilityRouter.conflictsAtEqualExclusivePrecedence(
                    scope, request.priority(), existing.getKey(), existing.getValue()));
            if (ambiguous) return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "An equal-priority exclusive binding overlaps an active route; adjust scope or priority first"));
        }
        UUID bindingId = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO extension_capability_bindings(binding_id,instance_id,capability_id,operation_name,scope,priority) VALUES (?,?,?,?,?::jsonb,?)",
                    bindingId, instanceId, request.capabilityId(), request.operationName(), write(scope), request.priority());
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "An identical capability binding already exists"));
        }
        audit(pluginId, actor, "BIND_CAPABILITY", Map.of("bindingId", bindingId.toString(), "capabilityId", request.capabilityId(),
                "operationName", request.operationName()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new BindingView(bindingId, instanceId, request.capabilityId(),
                request.operationName(), scope, request.priority(), "ACTIVE"));
    }

    @PostMapping("/routing/resolve")
    @Transactional(readOnly = true)
    public CapabilityRouter.RoutePlan resolveRoute(@RequestBody RouteRequest request) {
        if (request == null || request.capabilityId() == null || request.operationName() == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "capabilityId and operationName are required");
        }
        try {
            CapabilityRouter.validateBinding(request.capabilityId(), request.operationName(), request.scope());
        } catch (CapabilityRouter.RoutingException invalid) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
        var bindings = jdbc.query("SELECT b.binding_id,b.instance_id,b.capability_id,b.operation_name,b.scope,b.priority,b.eligibility_state,"
                        + "i.lifecycle_state,i.runtime_identity,i.plugin_id,i.release_id FROM extension_capability_bindings b "
                        + "JOIN extension_plugin_instances i ON i.instance_id=b.instance_id "
                        + "WHERE b.capability_id=? AND b.operation_name=?",
                (rs, row) -> {
                    Set<String> permissions = declaredPermissions(rs.getLong("release_id"), rs.getString("capability_id"), rs.getString("operation_name"));
                    String state = rs.getString("lifecycle_state");
                    return new CapabilityRouter.Binding(rs.getObject("binding_id", UUID.class), rs.getObject("instance_id", UUID.class),
                            rs.getString("capability_id"), rs.getString("operation_name"), read(rs.getString("scope"), STRING_MAP),
                            rs.getInt("priority"), permissions, CapabilityRouter.BindingState.valueOf(rs.getString("eligibility_state")),
                            routerState(state), "ENABLED".equals(state));
                }, request.capabilityId(), request.operationName());
        try {
            return new CapabilityRouter().resolve(new CapabilityRouter.RouteRequest(request.capabilityId(), request.operationName(),
                    request.scope(), request.pinnedInstanceId(), request.newCommitment()), bindings);
        } catch (CapabilityRouter.AmbiguousRouteException ambiguous) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, ambiguous.getMessage());
        } catch (CapabilityRouter.RoutingException unavailable) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getMessage());
        }
    }

    @PostMapping("/routing/invoke")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ResponseEntity<?> invokeCapability(@RequestBody CapabilityInvocationRequest request, JwtAuthenticationToken actor) {
        if (request == null || request.capabilityId() == null || request.operationName() == null || request.payload() == null
                || !(request.payload() instanceof Map<?, ?>) && !(request.payload() instanceof List<?>)) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "capabilityId, operationName and an object or array payload are required");
        }
        final String idempotencyKey;
        try {
            idempotencyKey = UUID.fromString(request.idempotencyKey()).toString();
            if (!idempotencyKey.equalsIgnoreCase(request.idempotencyKey())) throw new IllegalArgumentException();
            if (request.deadlineMillis() < 100 || request.deadlineMillis() > 30_000) throw new IllegalArgumentException();
            CapabilityRouter.validateBinding(request.capabilityId(), request.operationName(), request.scope());
        } catch (RuntimeException invalid) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid idempotency key, operation or deadline");
        }
        var operation = CapabilityRegistry.find(request.capabilityId()).orElseThrow()
                .operation(request.operationName()).orElseThrow();
        if (operation.policy() == CapabilityRegistry.Policy.PRIMARY_REPLICAS) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,
                    "Storage operations must use the Core media placement orchestrator");
        }
        String payloadJson = write(request.payload());
        String requestJson = write(new FingerprintInput(request.capabilityId(), request.operationName(),
                request.scope() == null ? Map.of() : new java.util.TreeMap<>(request.scope()), request.pinnedInstanceId(), request.payload()));
        String fingerprint = sha256(requestJson);
        OperationAttempt existing = operation.sideEffecting()
                ? findAttempt(request.capabilityId(), request.operationName(), idempotencyKey) : null;
        if (existing != null && !existing.requestFingerprint().equals(fingerprint)) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency key is already bound to a different capability request");
        }

        OperationPlanSnapshot snapshot;
        UUID attemptId = null;
        if (existing != null) {
            if ("SUCCEEDED".equals(existing.state())) return ResponseEntity.ok(read(existing.resultSnapshot(), OBJECT_MAP));
            if ("UNKNOWN".equals(existing.state()) || "RECONCILING".equals(existing.state())
                    || "IN_PROGRESS".equals(existing.state()) && existing.leaseUntil() != null && existing.leaseUntil().isAfter(java.time.Instant.now())) {
                return ResponseEntity.accepted().body(Map.of("attemptId", existing.attemptId(), "state", existing.state()));
            }
            snapshot = read(existing.planSnapshot(), OperationPlanSnapshot.class);
            payloadJson = encryption.decrypt(existing.requestSnapshot());
            attemptId = existing.attemptId();
        } else {
            CapabilityRouter.RoutePlan route;
            try {
                route = new CapabilityRouter().resolve(new CapabilityRouter.RouteRequest(request.capabilityId(), request.operationName(),
                        request.scope(), request.pinnedInstanceId(), request.newCommitment()), routeBindings(request.capabilityId(), request.operationName()));
            } catch (CapabilityRouter.AmbiguousRouteException ambiguous) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, ambiguous.getMessage());
            } catch (CapabilityRouter.RoutingException unavailable) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getMessage());
            }
            snapshot = snapshotPlan(route);
            if (snapshot.targets().isEmpty()) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No plugin instance is available");
            if (operation.sideEffecting()) {
                attemptId = UUID.randomUUID();
                TargetSnapshot primary = snapshot.targets().getFirst();
                String permissions = write(List.of(operation.requiredPermission()));
                int inserted = jdbc.update("INSERT INTO extension_operation_attempts(attempt_id,binding_id,instance_id,plugin_id,release_id,"
                                + "configuration_generation,external_account_fingerprint,capability_id,operation_name,scope_snapshot,permissions_snapshot,"
                                + "idempotency_key,request_fingerprint,request_snapshot,correlation_id,deadline_at,plan_snapshot) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?,?::jsonb) "
                                + "ON CONFLICT (capability_id,operation_name,idempotency_key) DO NOTHING",
                        attemptId, primary.bindingId(), primary.instanceId(), primary.pluginId(), primary.releaseId(),
                        primary.configurationGeneration(), primary.externalAccountFingerprint(), request.capabilityId(), request.operationName(),
                        write(request.scope() == null ? Map.of() : new java.util.TreeMap<>(request.scope())), permissions, idempotencyKey,
                        fingerprint, encryption.encrypt(payloadJson), UUID.randomUUID(), java.time.OffsetDateTime.now().plus(request.deadlineMillis(), java.time.temporal.ChronoUnit.MILLIS), write(snapshot));
                if (inserted == 0) {
                    existing = findAttempt(request.capabilityId(), request.operationName(), idempotencyKey);
                    if (existing == null || !existing.requestFingerprint().equals(fingerprint)) {
                        throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key was concurrently claimed by another request");
                    }
                    if ("SUCCEEDED".equals(existing.state())) return ResponseEntity.ok(read(existing.resultSnapshot(), OBJECT_MAP));
                    if ("UNKNOWN".equals(existing.state()) || "RECONCILING".equals(existing.state()) || "IN_PROGRESS".equals(existing.state())) {
                        return ResponseEntity.accepted().body(Map.of("attemptId", existing.attemptId(), "state", existing.state()));
                    }
                    attemptId = existing.attemptId();
                    snapshot = read(existing.planSnapshot(), OperationPlanSnapshot.class);
                    payloadJson = encryption.decrypt(existing.requestSnapshot());
                }
            }
        }

        UUID leaseToken = UUID.randomUUID();
        if (operation.sideEffecting()) {
            int claimed = jdbc.update("UPDATE extension_operation_attempts SET attempt_state='IN_PROGRESS',lease_token=?,"
                            + "lease_until=now()+interval '45 seconds',attempt_count=attempt_count+1,updated_at=now() "
                            + "WHERE attempt_id=? AND (attempt_state IN ('PENDING','FAILED') OR (attempt_state='IN_PROGRESS' AND lease_until<now()))",
                    leaseToken, attemptId);
            if (claimed == 0) {
                OperationAttempt latest = findAttempt(request.capabilityId(), request.operationName(), idempotencyKey);
                return ResponseEntity.accepted().body(Map.of("attemptId", latest.attemptId(), "state", latest.state()));
            }
        }

        List<Map<String, Object>> results = new java.util.ArrayList<>();
        boolean partial = false;
        try {
            if ("AGGREGATE_READ".equals(snapshot.policy()) && snapshot.targets().size() > 1) {
                AggregateReadResult aggregate = invokeAggregateRead(snapshot.targets(), request, idempotencyKey, payloadJson);
                results.addAll(aggregate.results());
                partial = aggregate.partial();
            } else {
                for (TargetSnapshot target : snapshot.targets()) {
                    var result = invokeTarget(target, request.capabilityId(), request.operationName(), idempotencyKey,
                            payloadJson, java.time.Duration.ofMillis(request.deadlineMillis()));
                    results.add(aggregateSuccess(target, request.operationName(), result));
                }
            }
            Map<String, Object> response = Map.of("attemptId", attemptId == null ? "read-only" : attemptId,
                    "policy", snapshot.policy(), "results", results, "partial", partial);
            if (operation.sideEffecting()) {
                String state = results.stream().allMatch(item -> ((Number) item.get("statusCode")).intValue() >= 200
                        && ((Number) item.get("statusCode")).intValue() < 300) ? "SUCCEEDED" : "FAILED";
                jdbc.update("UPDATE extension_operation_attempts SET attempt_state=?,result_snapshot=?::jsonb,last_error_code=NULL,"
                                + "lease_token=NULL,lease_until=NULL,updated_at=now() WHERE attempt_id=? AND lease_token=?",
                        state, write(response), attemptId, leaseToken);
            }
            audit(snapshot.targets().getFirst().pluginId(), actor, "CAPABILITY_INVOKE", Map.of("attemptId", attemptId == null ? "read-only" : attemptId.toString(),
                    "capabilityId", request.capabilityId(), "operationName", request.operationName(), "targetCount", results.size()));
            return ResponseEntity.ok(response);
        } catch (ExtensionRuntimePort.ExtensionTimeoutException timeout) {
            markAttemptUnknown(attemptId, leaseToken, "TIMEOUT");
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                    "Plugin outcome is unknown; retry or reconcile the same operation identity");
        } catch (org.springframework.web.server.ResponseStatusException unavailable) {
            markAttemptUnknown(attemptId, leaseToken, "RUNTIME_UNAVAILABLE");
            throw unavailable;
        } catch (ExtensionRuntimePort.ExtensionUnavailableException | ExtensionRuntimePort.ExtensionCallException
                | ExtensionRuntimePort.ExtensionProtocolException | JsonProcessingException failure) {
            markAttemptUnknown(attemptId, leaseToken, "PLUGIN_FAILURE");
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Plugin call failed; routing will not fall back to another instance");
        }
    }

    private AggregateReadResult invokeAggregateRead(List<TargetSnapshot> targets, CapabilityInvocationRequest request,
            String idempotencyKey, String payloadJson) {
        long deadlineNanos = System.nanoTime() + java.time.Duration.ofMillis(request.deadlineMillis()).toNanos();
        List<Map<String, Object>> results = new java.util.ArrayList<>();
        boolean partial = false;
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<ExtensionRuntimePort.InvocationResult>> calls = targets.stream()
                    .map(target -> executor.submit(() -> invokeTarget(target, request.capabilityId(), request.operationName(),
                            idempotencyKey, payloadJson, java.time.Duration.ofMillis(request.deadlineMillis()))))
                    .toList();
            for (int index = 0; index < targets.size(); index++) {
                TargetSnapshot target = targets.get(index);
                try {
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0) throw new java.util.concurrent.TimeoutException();
                    var result = calls.get(index).get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
                    results.add(aggregateSuccess(target, request.operationName(), result));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    calls.get(index).cancel(true);
                    results.add(aggregateFailure(target, request.operationName(), "INTERRUPTED", 503));
                    partial = true;
                } catch (java.util.concurrent.TimeoutException timeout) {
                    calls.get(index).cancel(true);
                    results.add(aggregateFailure(target, request.operationName(), "TIMEOUT", 504));
                    partial = true;
                } catch (java.util.concurrent.ExecutionException failed) {
                    results.add(aggregateFailure(target, request.operationName(), aggregateErrorCode(failed.getCause()),
                            aggregateStatus(failed.getCause())));
                    partial = true;
                } catch (JsonProcessingException invalidResponse) {
                    results.add(aggregateFailure(target, request.operationName(), "INVALID_RESPONSE", 502));
                    partial = true;
                }
            }
        }
        return new AggregateReadResult(List.copyOf(results), partial);
    }

    private ExtensionRuntimePort.InvocationResult invokeTarget(TargetSnapshot target, String capabilityId, String operation,
            String idempotencyKey, String payloadJson, java.time.Duration deadline) {
        Map<String, Object> runtimeConfiguration = new HashMap<>(target.publicConfiguration());
        target.encryptedSecrets().forEach((key, ciphertext) -> runtimeConfiguration.put(key, encryption.decrypt(ciphertext)));
        return runtime().invoke(target.runtimeIdentity(), target.runtimeCredentialGeneration(), capabilityId,
                operation, idempotencyKey, write(runtimeConfiguration), payloadJson, deadline);
    }

    private Map<String, Object> aggregateSuccess(TargetSnapshot target, String operation,
            ExtensionRuntimePort.InvocationResult result) throws JsonProcessingException {
        Object payload = mapper.readValue(result.responseJson(), Object.class);
        if (payload == null) payload = Map.of();
        return Map.of("instanceId", target.instanceId(), "resultId", target.instanceId() + ":" + operation,
                "statusCode", result.statusCode(), "payload", payload);
    }

    private static Map<String, Object> aggregateFailure(TargetSnapshot target, String operation, String code, int status) {
        return Map.of("instanceId", target.instanceId(), "resultId", target.instanceId() + ":" + operation,
                "statusCode", status, "errorCode", code);
    }

    private static String aggregateErrorCode(Throwable failure) {
        if (failure instanceof ExtensionRuntimePort.ExtensionTimeoutException) return "TIMEOUT";
        if (failure instanceof ExtensionRuntimePort.ExtensionCallException) return "PROVIDER_ERROR";
        if (failure instanceof JsonProcessingException || failure instanceof ExtensionRuntimePort.ExtensionProtocolException) return "INVALID_RESPONSE";
        if (failure instanceof IllegalArgumentException) return "INVALID_CONFIGURATION";
        return "UNAVAILABLE";
    }

    private static int aggregateStatus(Throwable failure) {
        if (failure instanceof ExtensionRuntimePort.ExtensionTimeoutException) return 504;
        if (failure instanceof ExtensionRuntimePort.ExtensionCallException call) return call.statusCode();
        if (failure instanceof ExtensionRuntimePort.ExtensionProtocolException || failure instanceof JsonProcessingException) return 502;
        return 503;
    }

    @PostMapping("/routing/attempts/{attemptId}/reconcile")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ResponseEntity<?> reconcileAttempt(@PathVariable UUID attemptId, JwtAuthenticationToken actor) {
        OperationAttempt attempt = findAttempt(attemptId);
        if (attempt == null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Operation attempt was not found");
        if (!"UNKNOWN".equals(attempt.state())) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,
                "Only an operation with an unknown provider outcome can be reconciled");
        UUID leaseToken = UUID.randomUUID();
        int claimed = jdbc.update("UPDATE extension_operation_attempts SET attempt_state='RECONCILING',lease_token=?,"
                        + "lease_until=now()+interval '45 seconds',attempt_count=attempt_count+1,updated_at=now() "
                        + "WHERE attempt_id=? AND attempt_state='UNKNOWN'",
                leaseToken, attemptId);
        if (claimed == 0) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,
                "Operation attempt is already being reconciled");

        OperationPlanSnapshot snapshot = read(attempt.planSnapshot(), OperationPlanSnapshot.class);
        String payloadJson = encryption.decrypt(attempt.requestSnapshot());
        try {
            List<Map<String, Object>> results = new java.util.ArrayList<>();
            for (int index = 0; index < snapshot.targets().size(); index++) {
                TargetSnapshot target = snapshot.targets().get(index);
                Map<String, Object> runtimeConfiguration = new HashMap<>(target.publicConfiguration());
                target.encryptedSecrets().forEach((key, ciphertext) -> runtimeConfiguration.put(key, encryption.decrypt(ciphertext)));
                String requestPayload = "PRIMARY_REPLICAS".equals(snapshot.policy())
                        ? withPlacementRole(payloadJson, index == 0 ? "PRIMARY" : "REPLICA") : payloadJson;
                var result = runtime().invoke(target.runtimeIdentity(), target.runtimeCredentialGeneration(), attempt.capabilityId(),
                        attempt.operationName(), attempt.idempotencyKey(), write(runtimeConfiguration), requestPayload, java.time.Duration.ofSeconds(10));
                results.add(Map.of("instanceId", target.instanceId(), "statusCode", result.statusCode(),
                        "payload", mapper.readValue(result.responseJson(), Object.class)));
            }
            Map<String, Object> response = Map.of("attemptId", attemptId, "policy", snapshot.policy(), "results", results);
            String state = results.stream().allMatch(item -> ((Number) item.get("statusCode")).intValue() >= 200
                    && ((Number) item.get("statusCode")).intValue() < 300) ? "SUCCEEDED" : "FAILED";
            String resultSnapshot = "storage.object.v1".equals(attempt.capabilityId()) && "get".equals(attempt.operationName())
                    ? write(Map.of("encryptedReadResult", encryption.encrypt(write(response)))) : write(response);
            jdbc.update("UPDATE extension_operation_attempts SET attempt_state=?,result_snapshot=?::jsonb,last_error_code=NULL,"
                            + "lease_token=NULL,lease_until=NULL,updated_at=now() WHERE attempt_id=? AND lease_token=?",
                    state, resultSnapshot, attemptId, leaseToken);
            audit(snapshot.targets().getFirst().pluginId(), actor, "CAPABILITY_RECONCILE", Map.of("attemptId", attemptId.toString(), "state", state));
            return ResponseEntity.ok(response);
        } catch (ExtensionRuntimePort.ExtensionTimeoutException timeout) {
            resetUnknownAttempt(attemptId, leaseToken, "TIMEOUT");
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Provider outcome remains unknown");
        } catch (org.springframework.web.server.ResponseStatusException unavailable) {
            resetUnknownAttempt(attemptId, leaseToken, "RUNTIME_UNAVAILABLE");
            throw unavailable;
        } catch (ExtensionRuntimePort.ExtensionUnavailableException | ExtensionRuntimePort.ExtensionCallException
                | ExtensionRuntimePort.ExtensionProtocolException | JsonProcessingException failure) {
            resetUnknownAttempt(attemptId, leaseToken, "PLUGIN_FAILURE");
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Provider outcome remains unknown; the original instance stays pinned");
        }
    }

    private String withPlacementRole(String payloadJson, String role) throws JsonProcessingException {
        var payload = mapper.readTree(payloadJson);
        if (payload == null || !payload.isObject()) throw new IllegalArgumentException("Object storage request must be a JSON object");
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("placementRole", role);
        return mapper.writeValueAsString(payload);
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
            setLegacyInstanceState(pluginId, "FAILED");
            refreshProvisioning(pluginId);
            audit(pluginId, actor, "INSTALL_FAILED", Map.of("errorClass", failure.getClass().getSimpleName()));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "Extension image could not be installed"));
        }
        jdbc.update("UPDATE extension_plugins SET lifecycle_state='INSTALLED',updated_at=now() WHERE plugin_id=?", pluginId);
        setLegacyInstanceState(pluginId, "INSTALLED");
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
            setLegacyInstanceState(pluginId, "FAILED");
            refreshProvisioning(pluginId);
            audit(pluginId, actor, "ENABLE_FAILED", Map.of("errorClass", failure.getClass().getSimpleName()));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "Extension did not become available"));
        }
        jdbc.update("UPDATE extension_plugins SET lifecycle_state='ENABLED',updated_at=now() WHERE plugin_id=?", pluginId);
        setLegacyInstanceState(pluginId, "ENABLED");
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
            setLegacyInstanceState(pluginId, "FAILED");
            refreshProvisioning(pluginId);
            audit(pluginId, actor, "CREDENTIAL_ROTATION_FAILED", Map.of("errorClass", failure.getClass().getSimpleName(), "generation", generation));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "Extension credentials could not be rotated"));
        }
        String state = restoreState == null ? (wasEnabled ? "ENABLED" : "DISABLED") : restoreState;
        jdbc.update("UPDATE extension_plugins SET lifecycle_state=?,rotation_was_enabled=false,rotation_previous_state=NULL,updated_at=now() WHERE plugin_id=? AND lifecycle_state='ROTATING'", state, pluginId);
        setLegacyInstanceState(pluginId, state);
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
        jdbc.update("INSERT INTO extension_plugin_releases(plugin_id,version,image_reference,manifest) VALUES (?,?,?,?::jsonb) ON CONFLICT (plugin_id,version) DO NOTHING",
                pluginId, replacement.version(), replacement.image(), write(replacement));
        Map<String, String> retainedSecrets = retainedSecrets(replacement, current.encryptedSecrets());
        jdbc.update("UPDATE extension_plugins SET manifest=?::jsonb,image_reference=?,public_configuration=?::jsonb,encrypted_secrets=?::jsonb,configuration_version=configuration_version+1,lifecycle_state='INSTALLED',updated_at=now() WHERE plugin_id=?",
                write(replacement), replacement.image(), write(configuration.publicValues()), write(retainedSecrets), pluginId);
        updateLegacyInstanceRelease(pluginId, replacement.version(), configuration.publicValues(), retainedSecrets, "INSTALLED");
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
        updateLegacyInstanceRelease(pluginId, previous.version(), configuration.publicValues(), retainedSecrets, "INSTALLED");
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
            setLegacyInstanceState(pluginId, "FAILED");
            refreshProvisioning(pluginId);
            audit(pluginId, actor, "DISABLE_FAILED", Map.of("errorClass", failure.getClass().getSimpleName()));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "Extension could not be stopped"));
        }
        jdbc.update("UPDATE extension_plugins SET lifecycle_state='DISABLED',updated_at=now() WHERE plugin_id=?", pluginId);
        setLegacyInstanceState(pluginId, "DISABLED");
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
        jdbc.update("UPDATE extension_plugin_instances SET public_configuration='{}'::jsonb,encrypted_secrets='{}'::jsonb,lifecycle_state='UNINSTALLED',updated_at=now() WHERE plugin_id=? AND instance_key='legacy-default'", pluginId);
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
        setLegacyInstanceState(pluginId, "CONFIGURED");
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
            Object response = mapper.readValue(result.responseJson(), Object.class);
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
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM extension_plugin_instances "
                + "WHERE plugin_id=? AND compatibility_mode='CAPABILITY_V2')", Boolean.class, pluginId))) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Configure a specific v2 plugin instance"));
        }
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
        jdbc.update("UPDATE extension_plugin_instances SET public_configuration=?::jsonb,encrypted_secrets=?::jsonb,"
                + "configuration_generation=configuration_generation+1,lifecycle_state='CONFIGURED',updated_at=now() "
                + "WHERE plugin_id=? AND compatibility_mode='LEGACY_V1' AND instance_key='legacy-default'",
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

    private void setLegacyInstanceState(String pluginId, String state) {
        jdbc.update("UPDATE extension_plugin_instances SET lifecycle_state=?,updated_at=now() "
                + "WHERE plugin_id=? AND compatibility_mode='LEGACY_V1' AND instance_key='legacy-default'", state, pluginId);
    }

    private void updateLegacyInstanceRelease(String pluginId, String version, Map<String, Object> publicConfiguration,
            Map<String, String> encryptedSecrets, String state) {
        Long releaseId = jdbc.queryForObject("SELECT release_id FROM extension_plugin_releases WHERE plugin_id=? AND version=?",
                Long.class, pluginId, version);
        jdbc.update("UPDATE extension_plugin_instances SET release_id=?,public_configuration=?::jsonb,encrypted_secrets=?::jsonb,"
                        + "configuration_generation=configuration_generation+1,lifecycle_state=?,updated_at=now() "
                        + "WHERE plugin_id=? AND instance_key='legacy-default' AND compatibility_mode='LEGACY_V1'",
                releaseId, write(publicConfiguration), write(encryptedSecrets), state, pluginId);
    }

    private Set<String> registeredPluginIds() {
        return Set.copyOf(jdbc.queryForList("SELECT plugin_id FROM extension_plugins WHERE lifecycle_state<>'UNINSTALLED'", String.class));
    }

    private Set<String> declaredPermissions(long releaseId, String capabilityId, String operationName) {
        PluginManifestV2 manifest = jdbc.query("SELECT manifest FROM extension_plugin_v2_releases WHERE release_id=?",
                rs -> rs.next() ? read(rs.getString(1), PluginManifestV2.class) : null, releaseId);
        if (manifest == null) return Set.of();
        return manifest.capabilities().stream().filter(capability -> capability.capabilityId().equals(capabilityId)
                        && capability.operations().contains(operationName))
                .flatMap(capability -> capability.permissions().stream()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private List<CapabilityRouter.Binding> routeBindings(String capabilityId, String operationName) {
        return jdbc.query("SELECT b.binding_id,b.instance_id,b.capability_id,b.operation_name,b.scope,b.priority,b.eligibility_state,"
                        + "i.lifecycle_state,i.release_id FROM extension_capability_bindings b "
                        + "JOIN extension_plugin_instances i ON i.instance_id=b.instance_id WHERE b.capability_id=? AND b.operation_name=?",
                (rs, row) -> {
                    String state = rs.getString("lifecycle_state");
                    return new CapabilityRouter.Binding(rs.getObject("binding_id", UUID.class), rs.getObject("instance_id", UUID.class),
                            rs.getString("capability_id"), rs.getString("operation_name"), read(rs.getString("scope"), STRING_MAP),
                            rs.getInt("priority"), declaredPermissions(rs.getLong("release_id"), rs.getString("capability_id"), rs.getString("operation_name")),
                            CapabilityRouter.BindingState.valueOf(rs.getString("eligibility_state")), routerState(state), "ENABLED".equals(state));
                }, capabilityId, operationName);
    }

    private OperationPlanSnapshot snapshotPlan(CapabilityRouter.RoutePlan route) {
        List<TargetSnapshot> targets = new java.util.ArrayList<>();
        for (int index = 0; index < route.instanceIds().size(); index++) {
            UUID instanceId = route.instanceIds().get(index);
            UUID bindingId = route.bindingIds().get(index);
            TargetSnapshot target = jdbc.query("SELECT b.binding_id,b.instance_id,i.plugin_id,i.runtime_identity,i.release_id,"
                            + "i.configuration_generation,i.external_account_fingerprint,i.public_configuration::text AS public_configuration,"
                            + "i.encrypted_secrets::text AS encrypted_secrets,p.credential_generation AS runtime_credential_generation "
                            + "FROM extension_capability_bindings b "
                            + "JOIN extension_plugin_instances i ON i.instance_id=b.instance_id "
                            + "JOIN extension_plugins p ON p.plugin_id=i.plugin_id "
                            + "WHERE b.binding_id=? AND b.instance_id=?",
                    rs -> rs.next() ? new TargetSnapshot(rs.getObject("binding_id", UUID.class), rs.getObject("instance_id", UUID.class),
                            rs.getString("plugin_id"), rs.getString("runtime_identity"), rs.getLong("release_id"),
                            rs.getInt("configuration_generation"), rs.getInt("runtime_credential_generation"),
                            rs.getString("external_account_fingerprint"),
                            read(rs.getString("public_configuration"), OBJECT_MAP), read(rs.getString("encrypted_secrets"), STRING_MAP)) : null,
                    bindingId, instanceId);
            if (target == null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,
                    "Capability binding changed while routing; retry with a new operation identity");
            targets.add(target);
        }
        return new OperationPlanSnapshot(route.capabilityId(), route.operationName(), route.policy().name(), route.sideEffecting(),
                route.scope(), List.copyOf(targets));
    }

    private OperationAttempt findAttempt(String capabilityId, String operationName, String idempotencyKey) {
        return jdbc.query("SELECT attempt_id,capability_id,operation_name,idempotency_key,request_fingerprint,attempt_state,plan_snapshot::text AS plan_snapshot,request_snapshot,"
                        + "result_snapshot::text AS result_snapshot,lease_until FROM extension_operation_attempts "
                        + "WHERE capability_id=? AND operation_name=? AND idempotency_key=?",
                rs -> rs.next() ? new OperationAttempt(rs.getObject("attempt_id", UUID.class), rs.getString("capability_id"),
                        rs.getString("operation_name"), rs.getString("idempotency_key"), rs.getString("request_fingerprint").trim(),
                        rs.getString("attempt_state"), rs.getString("plan_snapshot"), rs.getString("request_snapshot"), rs.getString("result_snapshot"),
                        rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toInstant()) : null,
                capabilityId, operationName, idempotencyKey);
    }

    private OperationAttempt findAttempt(UUID attemptId) {
        return jdbc.query("SELECT attempt_id,capability_id,operation_name,idempotency_key,request_fingerprint,attempt_state,plan_snapshot::text AS plan_snapshot,"
                        + "request_snapshot,result_snapshot::text AS result_snapshot,lease_until FROM extension_operation_attempts WHERE attempt_id=?",
                rs -> rs.next() ? new OperationAttempt(rs.getObject("attempt_id", UUID.class), rs.getString("capability_id"),
                        rs.getString("operation_name"), rs.getString("idempotency_key"), rs.getString("request_fingerprint").trim(),
                        rs.getString("attempt_state"), rs.getString("plan_snapshot"), rs.getString("request_snapshot"), rs.getString("result_snapshot"),
                        rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toInstant()) : null, attemptId);
    }

    private void markAttemptUnknown(UUID attemptId, UUID leaseToken, String errorCode) {
        if (attemptId == null) return;
        jdbc.update("UPDATE extension_operation_attempts SET attempt_state='UNKNOWN',last_error_code=?,lease_token=NULL,"
                        + "lease_until=NULL,updated_at=now() WHERE attempt_id=? AND lease_token=?",
                errorCode, attemptId, leaseToken);
    }

    private void resetUnknownAttempt(UUID attemptId, UUID leaseToken, String errorCode) {
        jdbc.update("UPDATE extension_operation_attempts SET attempt_state='UNKNOWN',last_error_code=?,lease_token=NULL,"
                        + "lease_until=NULL,updated_at=now() WHERE attempt_id=? AND lease_token=?",
                errorCode, attemptId, leaseToken);
    }

    private static String sha256(String value) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static CapabilityRouter.InstanceState routerState(String state) {
        return switch (state) {
            case "ENABLED" -> CapabilityRouter.InstanceState.ENABLED;
            case "DRAINING" -> CapabilityRouter.InstanceState.DRAINING;
            case "UNHEALTHY" -> CapabilityRouter.InstanceState.UNHEALTHY;
            case "FAILED" -> CapabilityRouter.InstanceState.FAILED;
            default -> CapabilityRouter.InstanceState.DISABLED;
        };
    }

    private static PluginManifest asRuntimeManifest(PluginManifest source) {
        return new PluginManifest("1", source.id(), source.name(), source.vendor(), source.version(), source.description(),
                source.minimumCoreVersion(), source.maximumCoreVersion(), source.image(), source.dependencies(), List.of(),
                source.configuration(), List.of(), source.healthPath(), source.diagnosticsPath(), source.geographicAvailability(),
                source.services(), source.persistentPaths(), source.configurationBindings());
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

    private InstanceRow findInstance(String pluginId, UUID instanceId) {
        return jdbc.query("SELECT i.instance_id,i.instance_key,i.release_id,i.compatibility_mode,i.lifecycle_state,i.configuration_generation,"
                        + "i.runtime_identity,i.public_configuration::text AS public_configuration,i.encrypted_secrets::text AS encrypted_secrets,"
                        + "r.manifest FROM extension_plugin_instances i JOIN extension_plugin_releases r ON r.release_id=i.release_id "
                        + "WHERE i.plugin_id=? AND i.instance_id=?",
                (rs, row) -> new InstanceRow(rs.getObject("instance_id", UUID.class), rs.getString("instance_key"),
                        rs.getLong("release_id"), rs.getString("compatibility_mode"), rs.getString("lifecycle_state"),
                        rs.getInt("configuration_generation"), rs.getString("runtime_identity"),
                        read(rs.getString("manifest"), PluginManifest.class), read(rs.getString("public_configuration"), OBJECT_MAP),
                        read(rs.getString("encrypted_secrets"), STRING_MAP)), pluginId, instanceId)
                .stream().findFirst().orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Plugin instance was not found"));
    }

    private PluginInstanceView instanceView(UUID instanceId) {
        return jdbc.query("SELECT instance_id,instance_key,release_id,compatibility_mode,lifecycle_state,configuration_generation,"
                        + "runtime_identity,(SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(public_configuration) AS keys(k)) AS public_keys,"
                        + "(SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(encrypted_secrets) AS keys(k)) AS secret_keys "
                        + "FROM extension_plugin_instances WHERE instance_id=?",
                (rs, row) -> new PluginInstanceView(rs.getObject("instance_id", UUID.class), rs.getString("instance_key"),
                        rs.getLong("release_id"), rs.getString("compatibility_mode"), rs.getString("lifecycle_state"),
                        rs.getInt("configuration_generation"), rs.getString("runtime_identity"),
                        arraySet(rs.getArray("public_keys")), arraySet(rs.getArray("secret_keys"))), instanceId)
                .stream().findFirst().orElseThrow();
    }

    private static Set<String> arraySet(java.sql.Array array) {
        if (array == null) return Set.of();
        try {
            Object[] values = (Object[]) array.getArray();
            return java.util.Arrays.stream(values).map(String::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet());
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Plugin instance configuration keys could not be read", failure);
        }
    }

    private Map<String, String> encryptSecrets(Map<String, String> submitted) {
        Map<String, String> encrypted = new HashMap<>();
        submitted.forEach((key, value) -> encrypted.put(key, encryption.encrypt(value)));
        return Map.copyOf(encrypted);
    }

    private PluginView view(PluginRow row) {
        return new PluginView(row.manifest(), row.lifecycleState(), row.publicConfiguration(),
                Set.copyOf(row.encryptedSecrets().keySet()), row.configurationVersion());
    }

    private Map<String, String> runtimeConfiguration(PluginRow row) {
        return runtimeConfiguration(row.manifest(), row.publicConfiguration(), row.encryptedSecrets());
    }

    private Map<String, String> runtimeConfiguration(PluginManifest manifest, Map<String, Object> publicConfiguration,
            Map<String, String> encryptedSecrets) {
        Map<String, String> values = new HashMap<>();
        Set<String> boundKeys = boundConfigurationKeys(manifest);
        publicConfiguration.forEach((key, value) -> {
            if (boundKeys.contains(key) && value != null) values.put(key, value instanceof String text ? text : String.valueOf(value));
        });
        encryptedSecrets.forEach((key, ciphertext) -> {
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
    private record InstanceRow(UUID instanceId, String instanceKey, long releaseId, String compatibilityMode,
            String lifecycleState, int configurationGeneration, String runtimeIdentity, PluginManifest manifest,
            Map<String, Object> publicConfiguration, Map<String, String> encryptedSecrets) { }
    public record ManifestValidationResponse(boolean valid, String coreVersion, List<String> errors) {
        public ManifestValidationResponse { errors = List.copyOf(errors); }
    }
    public record PluginSummary(String id, String name, String version, String lifecycleState,
                                int configurationVersion, Set<String> configuredSecrets,
                                PluginManifest.GeographicAvailability geographicAvailability, String compatibilityModes,
                                long instanceCount, long bindingCount, long subscriptionCount) { }
    public record PluginView(PluginManifest manifest, String lifecycleState, Map<String, Object> configuration,
                             Set<String> configuredSecrets, int configurationVersion) { }
    public record ConfigurationErrorResponse(List<String> errors) {
        public ConfigurationErrorResponse { errors = List.copyOf(errors); }
    }
    public record AuditView(UUID actorUserId, String action, Map<String, Object> details, java.time.Instant occurredAt) { }
    public record InvocationRequest(String extensionPoint, String operation, String idempotencyKey,
                                    Object payload, long deadlineMillis) { }
    public record InvocationResponse(int statusCode, Object payload) { }
    public record BindingRequest(String capabilityId, String operationName, Map<String, String> scope, int priority,
            UUID instanceId) { }
    public record BindingStateRequest(String state) { }
    public record BindingView(UUID bindingId, UUID instanceId, String capabilityId, String operationName,
                              Map<String, String> scope, int priority, String eligibilityState) { }
    public record SubscriptionView(UUID subscriptionId, UUID instanceId, String eventType, int schemaVersion,
                                   String subscriptionState) { }
    public record DeadEventDeliveryView(UUID deliveryId, String pluginId, String eventType, int attempts,
            String lastErrorCode, java.time.Instant createdAt) { }
    public record BackfillPreviewRequest(UUID subscriptionId, java.time.Instant from, java.time.Instant to,
            int maxEvents, int ratePerMinute) { }
    public record BackfillRunView(UUID runId, String pluginId, UUID subscriptionId, java.time.Instant from,
            java.time.Instant to, int ratePerMinute, String state, int eventCount, java.time.Instant createdAt,
            java.time.Instant executedAt) { }
    public record InstanceCreateRequest(String instanceKey, Map<String, Object> configuration) { }
    public record PluginInstanceView(UUID instanceId, String instanceKey, long releaseId, String compatibilityMode,
            String lifecycleState, int configurationGeneration, String runtimeIdentity,
            Set<String> publicConfigurationKeys, Set<String> configuredSecrets) { }
    public record InstanceObligationsView(UUID instanceId, long operations, long deliveries, long media,
            long pendingPayments, long activeShipments, long processingRefunds, long total) { }
    public record SubscriptionStateRequest(String state) { }
    public record RouteRequest(String capabilityId, String operationName, Map<String, String> scope,
                               UUID pinnedInstanceId, boolean newCommitment) { }
    public record CapabilityInvocationRequest(String capabilityId, String operationName, Map<String, String> scope,
            UUID pinnedInstanceId, boolean newCommitment, String idempotencyKey, Object payload, long deadlineMillis) { }
    private record AggregateReadResult(List<Map<String, Object>> results, boolean partial) { }
    private record FingerprintInput(String capabilityId, String operationName, Map<String, String> scope,
                                    UUID pinnedInstanceId, Object payload) { }
    private record TargetSnapshot(UUID bindingId, UUID instanceId, String pluginId, String runtimeIdentity,
                                  long releaseId, int configurationGeneration, int runtimeCredentialGeneration,
                                  String externalAccountFingerprint,
            Map<String, Object> publicConfiguration, Map<String, String> encryptedSecrets) {
        private TargetSnapshot {
            publicConfiguration = Map.copyOf(publicConfiguration);
            encryptedSecrets = Map.copyOf(encryptedSecrets);
        }
    }
    private record OperationPlanSnapshot(String capabilityId, String operationName, String policy, boolean sideEffecting,
            Map<String, String> scope, List<TargetSnapshot> targets) {
        private OperationPlanSnapshot { scope = Map.copyOf(scope); targets = List.copyOf(targets); }
    }
    private record OperationAttempt(UUID attemptId, String capabilityId, String operationName, String idempotencyKey,
                                    String requestFingerprint, String state, String planSnapshot, String requestSnapshot,
                                    String resultSnapshot, java.time.Instant leaseUntil) { }
}

