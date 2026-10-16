package io.artio.api.extensions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.extensions.PluginManifest;
import io.artio.extensions.PluginManifestV2;
import io.artio.extensions.PluginManifestValidator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Registers only a Marketplace release whose immutable descriptor Core has verified itself. */
@Service
public final class OfficialMarketplaceReleaseRegistration {
    private final MarketplaceCatalogClient marketplace;
    private final PluginManifestValidator manifestValidator;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final ExtensionInstallationJobOperations installationJobs;
    private final String coreVersion;

    public OfficialMarketplaceReleaseRegistration(MarketplaceCatalogClient marketplace,
            PluginManifestValidator manifestValidator, JdbcTemplate jdbc, ObjectMapper mapper,
            PlatformTransactionManager transactionManager, ExtensionInstallationJobOperations installationJobs,
            @Value("${artio.extensions.core-version:0.1.0}") String coreVersion) {
        this.marketplace = marketplace;
        this.manifestValidator = manifestValidator;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.installationJobs = installationJobs;
        this.coreVersion = coreVersion;
    }

    public Installation install(String pluginId, String version, UUID actorId, String idempotencyKey) {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "idempotencyKey must contain 8 to 128 safe characters");
        }
        MarketplaceCatalogClient.VerifiedReleaseDescriptor verified =
                marketplace.verifiedReleaseDescriptor(pluginId, version);
        JsonNode descriptor = verified.descriptor();
        JsonNode signedManifest = descriptor.path("manifest");
        try {
            if (signedManifest.has("packageManifest")) {
                PluginManifestV2 manifest = mapper.treeToValue(signedManifest, PluginManifestV2.class);
                return transactions.execute(status -> installV2(manifest, descriptor, verified.digest(), actorId, idempotencyKey));
            }
            PluginManifest manifest = mapper.treeToValue(signedManifest, PluginManifest.class);
            return transactions.execute(status -> installV1(manifest, descriptor, verified.digest(), actorId, idempotencyKey));
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Verified Marketplace release could not be registered", failure);
        }
    }

    private Installation installV1(PluginManifest manifest, JsonNode descriptor, String digest, UUID actorId, String idempotencyKey) {
        requireIdentity(manifest.id(), manifest.version(), descriptor);
        lockIdempotency(actorId, idempotencyKey);
        ExtensionInstallationJobOperations.JobView existing = existingMarketplaceJob(manifest.id(), digest, actorId, idempotencyKey);
        if (existing != null) return new Installation(manifest.id(), manifest.version(), digest, existing);
        ExtensionSecurityPolicy.requireAllowed(jdbc, manifest);
        List<String> errors = manifestValidator.validate(manifest, coreVersion, registeredPluginIds());
        if (!errors.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("; ", errors));
        requireUnregistered(manifest.id());
        jdbc.update("INSERT INTO extension_plugins(plugin_id,manifest,image_reference,lifecycle_state) VALUES (?,?::jsonb,?,'VALIDATED')",
                manifest.id(), write(manifest), manifest.image());
        jdbc.update("INSERT INTO extension_plugin_releases(plugin_id,version,image_reference,manifest) VALUES (?,?,?,?::jsonb)",
                manifest.id(), manifest.version(), manifest.image(), write(manifest));
        long releaseId = releaseId(manifest.id(), manifest.version());
        persistProvenance(releaseId, digest, descriptor, actorId, manifest.id(), manifest.version());
        UUID instanceId = UUID.randomUUID();
        jdbc.update("INSERT INTO extension_plugin_instances(instance_id,plugin_id,instance_key,release_id,compatibility_mode,lifecycle_state,runtime_identity) "
                        + "VALUES (?,?, 'legacy-default',?,'LEGACY_V1','CONFIGURED',?)",
                instanceId, manifest.id(), releaseId, manifest.id());
        var job = installationJobs.requestMarketplace(manifest.id(), null, "legacy:" + manifest.id(), releaseId,
                manifest, actorId, idempotencyKey, digest);
        return new Installation(manifest.id(), manifest.version(), digest, job);
    }

    private Installation installV2(PluginManifestV2 manifest, JsonNode descriptor, String digest, UUID actorId, String idempotencyKey) {
        PluginManifest source = manifest.packageManifest();
        requireIdentity(source.id(), source.version(), descriptor);
        lockIdempotency(actorId, idempotencyKey);
        ExtensionInstallationJobOperations.JobView existing = existingMarketplaceJob(source.id(), digest, actorId, idempotencyKey);
        if (existing != null) return new Installation(source.id(), source.version(), digest, existing);
        PluginManifest runtimeManifest = asRuntimeManifest(source);
        ExtensionSecurityPolicy.requireAllowed(jdbc, runtimeManifest);
        List<String> errors = manifestValidator.validate(manifest, coreVersion, registeredPluginIds());
        if (!errors.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("; ", errors));
        requireUnregistered(source.id());
        jdbc.update("INSERT INTO extension_plugins(plugin_id,manifest,image_reference,lifecycle_state) VALUES (?,?::jsonb,?,'VALIDATED')",
                source.id(), write(runtimeManifest), source.image());
        jdbc.update("INSERT INTO extension_plugin_releases(plugin_id,version,image_reference,manifest) VALUES (?,?,?,?::jsonb)",
                source.id(), source.version(), source.image(), write(runtimeManifest));
        long releaseId = releaseId(source.id(), source.version());
        jdbc.update("INSERT INTO extension_plugin_v2_releases(release_id,plugin_id,manifest) VALUES (?,?,?::jsonb)",
                releaseId, source.id(), write(manifest));
        persistProvenance(releaseId, digest, descriptor, actorId, source.id(), source.version());
        UUID instanceId = UUID.randomUUID();
        jdbc.update("INSERT INTO extension_plugin_instances(instance_id,plugin_id,instance_key,release_id,compatibility_mode,lifecycle_state,runtime_identity) "
                        + "VALUES (?,?, 'default',?,'CAPABILITY_V2','CONFIGURED',?)",
                instanceId, source.id(), releaseId, source.id());
        for (PluginManifestV2.EventSubscription subscription : manifest.subscriptions()) {
            jdbc.update("INSERT INTO extension_event_subscriptions(subscription_id,instance_id,event_type,schema_version,permissions_snapshot) "
                            + "VALUES (?,?,?,?,?::jsonb)",
                    UUID.randomUUID(), instanceId, subscription.eventType(), subscription.schemaVersion(), write(subscription.permissions()));
        }
        var job = installationJobs.requestMarketplace(source.id(), instanceId, source.id(), releaseId,
                runtimeManifest, actorId, idempotencyKey, digest);
        return new Installation(source.id(), source.version(), digest, job);
    }

    private ExtensionInstallationJobOperations.JobView existingMarketplaceJob(String pluginId, String digest,
            UUID actorId, String idempotencyKey) {
        ExtensionInstallationJobOperations.JobView existing = installationJobs.findByIdempotency(actorId, idempotencyKey);
        if (existing == null) return null;
        if (!pluginId.equals(existing.pluginId()) || !"MARKETPLACE".equals(existing.sourceKind())
                || !digest.equals(existing.releaseDigest())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key was already used for another installation");
        }
        return existing;
    }

    private void lockIdempotency(UUID actorId, String idempotencyKey) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> { }, actorId + ":" + idempotencyKey);
    }

    private void persistProvenance(long releaseId, String digest, JsonNode descriptor,
            UUID actorId, String pluginId, String version) {
        jdbc.update("INSERT INTO extension_plugin_release_provenance(release_id,provenance,descriptor_digest,descriptor_snapshot) "
                        + "VALUES (?,'OFFICIAL_MARKETPLACE',?,?::jsonb)",
                releaseId, digest, write(descriptor));
        jdbc.update("INSERT INTO extension_audit_events(id,plugin_id,actor_user_id,action,details) VALUES (?,?,?,'MARKETPLACE_RELEASE_REGISTERED',?::jsonb)",
                UUID.randomUUID(), pluginId, actorId,
                write(Map.of("version", version, "releaseId", releaseId, "descriptorDigest", digest,
                        "provenance", "OFFICIAL_MARKETPLACE")));
    }

    private void requireIdentity(String pluginId, String version, JsonNode descriptor) {
        if (!pluginId.equals(descriptor.path("pluginId").asText())
                || !version.equals(descriptor.path("version").asText())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Marketplace descriptor identity changed after verification");
        }
    }

    private void requireUnregistered(String pluginId) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM extension_plugins WHERE plugin_id=?)", Boolean.class, pluginId);
        if (Boolean.TRUE.equals(exists)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Plugin is already registered");
    }

    private Set<String> registeredPluginIds() {
        return Set.copyOf(jdbc.queryForList("SELECT plugin_id FROM extension_plugins WHERE lifecycle_state<>'UNINSTALLED'", String.class));
    }

    private long releaseId(String pluginId, String version) {
        Long id = jdbc.queryForObject("SELECT release_id FROM extension_plugin_releases WHERE plugin_id=? AND version=?",
                Long.class, pluginId, version);
        return id;
    }

    private static PluginManifest asRuntimeManifest(PluginManifest source) {
        return new PluginManifest("1", source.id(), source.name(), source.vendor(), source.version(), source.description(),
                source.minimumCoreVersion(), source.maximumCoreVersion(), source.image(), source.dependencies(), List.of(),
                source.configuration(), List.of(), source.healthPath(), source.diagnosticsPath(), source.geographicAvailability(),
                source.services(), source.persistentPaths(), source.configurationBindings(), source.secretFileBindings());
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Marketplace release metadata could not be serialized", failure); }
    }

    public record Installation(String pluginId, String version, String descriptorDigest,
            ExtensionInstallationJobOperations.JobView job) { }
}
