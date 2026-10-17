package io.artio.api.extensions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.extensions.PluginManifest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Persists operator intent before runtime work starts, so installs survive API restarts. */
@Service
public class ExtensionInstallationJobOperations {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ExtensionInstallationJobOperations(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public JobView request(String pluginId, UUID instanceId, String runtimeIdentity, long releaseId,
            PluginManifest manifest, UUID actorId, String idempotencyKey) {
        return request(pluginId, instanceId, runtimeIdentity, releaseId, manifest, actorId, idempotencyKey,
                "REGISTERED", null, null);
    }

    @Transactional
    public JobView requestMarketplace(String pluginId, UUID instanceId, String runtimeIdentity, long releaseId,
            PluginManifest manifest, UUID actorId, String idempotencyKey, String descriptorDigest) {
        if (descriptorDigest == null || !descriptorDigest.matches("[a-f0-9]{64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Verified Marketplace descriptor digest is invalid");
        }
        return request(pluginId, instanceId, runtimeIdentity, releaseId, manifest, actorId, idempotencyKey,
                "MARKETPLACE", null, descriptorDigest);
    }

    @Transactional
    public JobView requestReleaseChange(String pluginId, UUID instanceId, String runtimeIdentity,
            long previousReleaseId, long targetReleaseId, PluginManifest previousManifest, PluginManifest targetManifest,
            String previousLifecycleState, Map<String, Object> targetConfiguration,
            Map<String, String> targetEncryptedSecrets, int targetConfigurationGeneration,
            String operationKind, UUID actorId, String idempotencyKey,
            String sourceKind, String releaseDigest) {
        if (instanceId == null || previousReleaseId <= 0 || targetReleaseId <= 0
                || previousReleaseId == targetReleaseId || previousManifest == null || targetManifest == null
                || !Set.of("UPDATE", "ROLLBACK").contains(operationKind)
                || !Set.of("DRAINING", "DISABLED", "INSTALLED").contains(previousLifecycleState)
                || !Set.of("REGISTERED", "MARKETPLACE").contains(sourceKind)
                || (releaseDigest != null && !releaseDigest.matches("[a-f0-9]{64}"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Plugin release change request is invalid");
        }
        if (!pluginId.equals(previousManifest.id()) || !pluginId.equals(targetManifest.id())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A release change must retain its plugin identity");
        }
        ExtensionSecurityPolicy.requireAllowed(jdbc, targetManifest);
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "idempotencyKey must contain 8 to 128 safe characters");
        }
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> { }, actorId + ":" + idempotencyKey);
        ExistingJob existing = jdbc.query("SELECT job_id,plugin_id,instance_id,source_kind,source_release_id,release_digest,operation_kind,previous_release_id "
                        + "FROM extension_installation_jobs WHERE requested_by=? AND idempotency_key=?",
                rs -> rs.next() ? new ExistingJob(rs.getObject("job_id", UUID.class), rs.getString("plugin_id"),
                        rs.getObject("instance_id", UUID.class), rs.getString("source_kind"),
                        (Long) rs.getObject("source_release_id"), rs.getString("release_digest"),
                        rs.getString("operation_kind"), (Long) rs.getObject("previous_release_id")) : null,
                actorId, idempotencyKey);
        if (existing != null) {
            if (!pluginId.equals(existing.pluginId()) || !instanceId.equals(existing.instanceId())
                    || !operationKind.equals(existing.operationKind()) || !sourceKind.equals(existing.sourceKind())
                    || !Long.valueOf(targetReleaseId).equals(existing.releaseId())
                    || !Long.valueOf(previousReleaseId).equals(existing.previousReleaseId())
                    || !java.util.Objects.equals(releaseDigest, existing.releaseDigest())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key was already used for another release change");
            }
            return get(existing.jobId());
        }

        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> { },
                "installation:" + pluginId + ":" + instanceId);
        InstanceTarget target = jdbc.query("SELECT release_id,lifecycle_state,runtime_identity FROM extension_plugin_instances "
                        + "WHERE instance_id=? AND plugin_id=? FOR UPDATE",
                rs -> rs.next() ? new InstanceTarget(rs.getLong("release_id"), rs.getString("lifecycle_state"),
                        rs.getString("runtime_identity")) : null, instanceId, pluginId);
        if (target == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plugin instance was not found");
        if (target.releaseId() != previousReleaseId || !runtimeIdentity.equals(target.runtimeIdentity())
                || !previousLifecycleState.equals(target.lifecycleState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Plugin instance changed while the release operation was prepared");
        }
        Boolean targetReleaseRegistered = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM extension_plugin_releases r "
                + "JOIN extension_plugin_v2_releases v ON v.release_id=r.release_id WHERE r.release_id=? AND r.plugin_id=?)",
                Boolean.class, targetReleaseId, pluginId);
        if (!Boolean.TRUE.equals(targetReleaseRegistered)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Target must be a registered immutable v2 release");
        }
        Boolean active = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM extension_installation_jobs WHERE plugin_id=? "
                + "AND instance_id=? AND state IN ('REQUESTED','POLICY_CHECK','VERIFYING_SOURCE','DOWNLOADING_OR_IMPORTING','VERIFYING_ARTIFACTS','REGISTERING_RELEASE','WAITING_FOR_OBLIGATIONS'))",
                Boolean.class, pluginId, instanceId);
        if (Boolean.TRUE.equals(active)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A lifecycle operation is already active for this plugin instance");
        }
        if (unresolvedObligations(instanceId) > 0 && !"DRAINING".equals(previousLifecycleState)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Drain and settle this instance's existing obligations before changing its release");
        }

        var jobId = UUID.randomUUID();
        String manifestJson = write(targetManifest);
        String permissionJson = write(Map.of("permissions", targetManifest.permissions()));
        List<Map<String, String>> images = new java.util.ArrayList<>();
        images.add(Map.of("id", "primary", "image", targetManifest.image()));
        targetManifest.services().forEach(service -> images.add(Map.of("id", service.id(), "image", service.image())));
        String configurationJson = write(Map.of("publicConfiguration", targetConfiguration,
                "encryptedSecrets", targetEncryptedSecrets, "configurationGeneration", targetConfigurationGeneration));
        jdbc.update("INSERT INTO extension_installation_jobs(job_id,plugin_id,instance_id,runtime_identity,source_kind,operation_kind,"
                        + "source_release_id,previous_release_id,previous_manifest_snapshot,previous_lifecycle_state,configuration_snapshot,release_digest,requested_by,"
                        + "idempotency_key,manifest_snapshot,permission_snapshot,image_snapshot,state) "
                        + "VALUES (?,?,?,?,?,?,?,?,?::jsonb,?,?::jsonb,?,?,?,?::jsonb,?::jsonb,?::jsonb,'REQUESTED')",
                jobId, pluginId, instanceId, runtimeIdentity, sourceKind, operationKind, targetReleaseId, previousReleaseId,
                write(previousManifest), previousLifecycleState, configurationJson, releaseDigest, actorId, idempotencyKey,
                manifestJson, permissionJson, write(images));
        String pendingState = Set.of("ENABLED", "DRAINING").contains(previousLifecycleState) ? "DRAINING" : previousLifecycleState;
        jdbc.update("UPDATE extension_plugin_instances SET lifecycle_state=?,updated_at=now() WHERE instance_id=? AND plugin_id=?",
                pendingState, instanceId, pluginId);
        return get(jobId);
    }

    @Transactional(readOnly = true)
    public JobView findByIdempotency(UUID actorId, String idempotencyKey) {
        UUID jobId = jdbc.query("SELECT job_id FROM extension_installation_jobs WHERE requested_by=? AND idempotency_key=?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, actorId, idempotencyKey);
        return jobId == null ? null : get(jobId);
    }

    @Transactional
    public JobView requestArchive(String pluginId, UUID instanceId, String runtimeIdentity, long releaseId,
            PluginManifest manifest, UUID actorId, String idempotencyKey, String quarantineName,
            String archiveSha256, long archiveBytes) {
        return request(pluginId, instanceId, runtimeIdentity, releaseId, manifest, actorId, idempotencyKey,
                "ARCHIVE", new ArchiveMetadata(quarantineName, archiveSha256, archiveBytes), null);
    }

    private JobView request(String pluginId, UUID instanceId, String runtimeIdentity, long releaseId,
            PluginManifest manifest, UUID actorId, String idempotencyKey, String sourceKind) {
        return request(pluginId, instanceId, runtimeIdentity, releaseId, manifest, actorId, idempotencyKey,
                sourceKind, null, null);
    }

    private JobView request(String pluginId, UUID instanceId, String runtimeIdentity, long releaseId,
            PluginManifest manifest, UUID actorId, String idempotencyKey, String sourceKind,
            ArchiveMetadata archive, String releaseDigest) {
        ExtensionSecurityPolicy.requireAllowed(jdbc, manifest);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> { }, actorId + ":" + idempotencyKey);
        ExistingJob existing = jdbc.query("SELECT job_id,plugin_id,instance_id,source_kind,source_release_id,release_digest,operation_kind,previous_release_id "
                        + "FROM extension_installation_jobs WHERE requested_by=? AND idempotency_key=?",
                rs -> rs.next() ? new ExistingJob(rs.getObject("job_id", UUID.class), rs.getString("plugin_id"),
                        rs.getObject("instance_id", UUID.class), rs.getString("source_kind"),
                        (Long) rs.getObject("source_release_id"), rs.getString("release_digest"),
                        rs.getString("operation_kind"), (Long) rs.getObject("previous_release_id")) : null,
                actorId, idempotencyKey);
        if (existing != null) {
            if (!existing.pluginId().equals(pluginId) || !java.util.Objects.equals(existing.instanceId(), instanceId)
                    || !sourceKind.equals(existing.sourceKind())
                    || !java.util.Objects.equals(existing.releaseId(), releaseId > 0 ? releaseId : null)
                    || !"INSTALL".equals(existing.operationKind()) || existing.previousReleaseId() != null
                    || !java.util.Objects.equals(existing.releaseDigest(), releaseDigest)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key was already used for another installation");
            }
            if (archive != null) {
                String existingDigest = jdbc.query("SELECT archive_sha256 FROM extension_local_installation_archives WHERE job_id=?",
                        rs -> rs.next() ? rs.getString(1) : null, existing.jobId());
                if (!archive.sha256().equals(existingDigest)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key was already used for another archive");
                }
            }
            return get(existing.jobId());
        }
        String subjectLock = "installation:" + pluginId + ":" + (instanceId == null ? "legacy" : instanceId);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> { }, subjectLock);
        Boolean active = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM extension_installation_jobs WHERE plugin_id=? "
                        + "AND instance_id IS NOT DISTINCT FROM ?::uuid AND state IN "
                        + "('REQUESTED','POLICY_CHECK','VERIFYING_SOURCE','DOWNLOADING_OR_IMPORTING','VERIFYING_ARTIFACTS','REGISTERING_RELEASE','WAITING_FOR_OBLIGATIONS'))",
                Boolean.class, pluginId, instanceId);
        if (Boolean.TRUE.equals(active)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An installation job is already active for this plugin target");
        }
        UUID jobId = UUID.randomUUID();
        String manifestJson = write(manifest);
        String permissionJson = write(Map.of("permissions", manifest.permissions()));
        List<Map<String, String>> images = new java.util.ArrayList<>();
        images.add(Map.of("id", "primary", "image", manifest.image()));
        manifest.services().forEach(service -> images.add(Map.of("id", service.id(), "image", service.image())));
        String imageJson = write(images);
        jdbc.update("INSERT INTO extension_installation_jobs(job_id,plugin_id,instance_id,runtime_identity,source_kind,operation_kind,source_release_id,release_digest,"
                        + "requested_by,idempotency_key,manifest_snapshot,permission_snapshot,image_snapshot,state) "
                        + "VALUES (?,?,?,?,?,'INSTALL',?,?,?,?,?::jsonb,?::jsonb,?::jsonb,'REQUESTED')",
                jobId, pluginId, instanceId, runtimeIdentity, sourceKind, releaseId > 0 ? releaseId : null, releaseDigest,
                actorId, idempotencyKey, manifestJson, permissionJson, imageJson);
        if (archive != null) {
            jdbc.update("INSERT INTO extension_local_installation_archives(job_id,quarantine_name,archive_sha256,archive_bytes) "
                            + "VALUES (?,?,?,?)",
                    jobId, archive.quarantineName(), archive.sha256(), archive.bytes());
            jdbc.update("INSERT INTO extension_audit_events(id,plugin_id,actor_user_id,action,details) VALUES (?,?,?,'LOCAL_ARCHIVE_UPLOAD',?::jsonb)",
                    UUID.randomUUID(), pluginId, actorId,
                    write(Map.of("jobId", jobId.toString(), "archiveSha256", archive.sha256(), "archiveBytes", archive.bytes())));
        }
        return get(jobId);
    }

    @Transactional(readOnly = true)
    public JobView get(UUID jobId) {
        return jdbc.query("SELECT job_id,plugin_id,instance_id,runtime_identity,source_kind,operation_kind,source_release_id,previous_release_id,release_digest,state,attempt_count,error_code,created_at,updated_at,completed_at "
                        + "FROM extension_installation_jobs WHERE job_id=?",
                (rs, row) -> new JobView(rs.getObject("job_id", UUID.class), rs.getString("plugin_id"),
                        rs.getObject("instance_id", UUID.class), rs.getString("runtime_identity"), rs.getString("source_kind"),
                        rs.getString("operation_kind"), (Long) rs.getObject("source_release_id"),
                        (Long) rs.getObject("previous_release_id"), rs.getString("release_digest"), rs.getString("state"),
                        rs.getInt("attempt_count"), rs.getString("error_code"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(), rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()), jobId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Installation job was not found"));
    }

    @Transactional(readOnly = true)
    public List<JobView> list(String pluginId) {
        String sql = "SELECT job_id,plugin_id,instance_id,runtime_identity,source_kind,operation_kind,source_release_id,previous_release_id,release_digest,state,attempt_count,error_code,created_at,updated_at,completed_at "
                + "FROM extension_installation_jobs " + (pluginId == null ? "" : "WHERE plugin_id=? ") + "ORDER BY created_at DESC LIMIT 100";
        var mapper = (org.springframework.jdbc.core.RowMapper<JobView>) (rs, row) -> new JobView(
                rs.getObject("job_id", UUID.class), rs.getString("plugin_id"), rs.getObject("instance_id", UUID.class),
                rs.getString("runtime_identity"), rs.getString("source_kind"), rs.getString("operation_kind"),
                (Long) rs.getObject("source_release_id"), (Long) rs.getObject("previous_release_id"),
                rs.getString("release_digest"), rs.getString("state"), rs.getInt("attempt_count"), rs.getString("error_code"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());
        return pluginId == null ? jdbc.query(sql, mapper) : jdbc.query(sql, mapper, pluginId);
    }

    @Transactional
    public JobView retry(UUID jobId) {
        Target target = jdbc.query("SELECT plugin_id,instance_id,manifest_snapshot::text FROM extension_installation_jobs WHERE job_id=? AND state='FAILED'",
                rs -> rs.next() ? new Target(rs.getString("plugin_id"), rs.getObject("instance_id", UUID.class),
                        readManifest(rs.getString(3))) : null, jobId);
        if (target == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only failed installation jobs can be retried");
        ExtensionSecurityPolicy.requireAllowed(jdbc, target.manifest());
        String subjectLock = "installation:" + target.pluginId() + ":" + (target.instanceId() == null ? "legacy" : target.instanceId());
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> { }, subjectLock);
        Boolean active = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM extension_installation_jobs WHERE plugin_id=? "
                        + "AND instance_id IS NOT DISTINCT FROM ?::uuid AND job_id<>? AND state IN "
                        + "('REQUESTED','POLICY_CHECK','VERIFYING_SOURCE','DOWNLOADING_OR_IMPORTING','VERIFYING_ARTIFACTS','REGISTERING_RELEASE','WAITING_FOR_OBLIGATIONS'))",
                Boolean.class, target.pluginId(), target.instanceId(), jobId);
        if (Boolean.TRUE.equals(active)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Another installation job is active for this plugin target");
        int updated = jdbc.update("UPDATE extension_installation_jobs SET state='REQUESTED',attempt_count=0,error_code=NULL,"
                        + "lease_token=NULL,lease_until=NULL,completed_at=NULL,updated_at=now() WHERE job_id=? AND state='FAILED'", jobId);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only failed installation jobs can be retried");
        return get(jobId);
    }

    @Transactional
    public JobView cancel(UUID jobId) {
        int updated = jdbc.update("UPDATE extension_installation_jobs SET state='CANCELLED',lease_token=NULL,lease_until=NULL,"
                        + "completed_at=now(),updated_at=now() WHERE job_id=? AND state='REQUESTED'", jobId);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Installation has started and cannot be cancelled safely");
        return get(jobId);
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("Could not snapshot installation metadata", failure); }
    }

    private long unresolvedObligations(UUID instanceId) {
        Long count = jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM extension_operation_attempts WHERE instance_id=? AND attempt_state IN ('PENDING','IN_PROGRESS','UNKNOWN','RECONCILING'))
                + (SELECT count(*) FROM extension_event_deliveries WHERE instance_id=? AND state<>'DELIVERED')
                + (SELECT count(*) FROM catalog_media_storage_objects WHERE object_state IN ('ACTIVE','DELETE_PENDING')
                    AND (primary_instance_id=? OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(replica_instance_ids) r(instance_id) WHERE r.instance_id=?)))
                + (SELECT count(*) FROM payments WHERE provider_instance_id=? AND status='PENDING')
                + (SELECT count(*) FROM order_shipments s JOIN orders o ON o.id=s.order_id WHERE s.provider_instance_id=?
                    AND o.status IN ('CONFIRMED','PAID','PARTIALLY_SHIPPED','SHIPPED'))
                + (SELECT count(*) FROM payment_refunds r JOIN payments p ON p.id=r.payment_id
                    WHERE p.provider_instance_id=? AND r.status='PROCESSING')
                """, Long.class, instanceId, instanceId, instanceId, instanceId.toString(), instanceId, instanceId, instanceId);
        return count == null ? 0 : count;
    }

    private PluginManifest readManifest(String json) {
        try { return mapper.readValue(json, PluginManifest.class); }
        catch (Exception failure) { throw new IllegalStateException("Persisted installation manifest is invalid", failure); }
    }

    public record JobView(UUID jobId, String pluginId, UUID instanceId, String runtimeIdentity, String sourceKind,
            String operationKind, Long releaseId, Long previousReleaseId, String releaseDigest, String state, int attemptCount, String errorCode,
            java.time.Instant createdAt, java.time.Instant updatedAt, java.time.Instant completedAt) { }
    private record Target(String pluginId, UUID instanceId, PluginManifest manifest) { }
    private record ArchiveMetadata(String quarantineName, String sha256, long bytes) { }
    private record ExistingJob(UUID jobId, String pluginId, UUID instanceId, String sourceKind,
            Long releaseId, String releaseDigest, String operationKind, Long previousReleaseId) { }
    private record InstanceTarget(long releaseId, String lifecycleState, String runtimeIdentity) { }
}
