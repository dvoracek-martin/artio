package io.artio.api.extensions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.extensions.PluginManifest;
import java.util.List;
import java.util.Map;
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
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> { }, actorId + ":" + idempotencyKey);
        JobView existing = jdbc.query("SELECT job_id,plugin_id,instance_id FROM extension_installation_jobs WHERE requested_by=? AND idempotency_key=?",
                rs -> rs.next() ? new JobView(rs.getObject("job_id", UUID.class), rs.getString("plugin_id"),
                        rs.getObject("instance_id", UUID.class), null, null, null, null, null, 0, null, null, null, null) : null,
                actorId, idempotencyKey);
        if (existing != null) {
            if (!existing.pluginId().equals(pluginId) || !java.util.Objects.equals(existing.instanceId(), instanceId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key was already used for another installation");
            }
            return get(existing.jobId());
        }
        String subjectLock = "installation:" + pluginId + ":" + (instanceId == null ? "legacy" : instanceId);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> { }, subjectLock);
        Boolean active = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM extension_installation_jobs WHERE plugin_id=? "
                        + "AND instance_id IS NOT DISTINCT FROM ?::uuid AND state IN "
                        + "('REQUESTED','POLICY_CHECK','VERIFYING_SOURCE','DOWNLOADING_OR_IMPORTING','VERIFYING_ARTIFACTS','REGISTERING_RELEASE'))",
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
        jdbc.update("INSERT INTO extension_installation_jobs(job_id,plugin_id,instance_id,runtime_identity,source_kind,source_release_id,"
                        + "requested_by,idempotency_key,manifest_snapshot,permission_snapshot,image_snapshot,state) VALUES (?,?,?,?,'REGISTERED',?,?,?,?::jsonb,?::jsonb,?::jsonb,'REQUESTED')",
                jobId, pluginId, instanceId, runtimeIdentity, releaseId > 0 ? releaseId : null, actorId, idempotencyKey,
                manifestJson, permissionJson, imageJson);
        return get(jobId);
    }

    @Transactional(readOnly = true)
    public JobView get(UUID jobId) {
        return jdbc.query("SELECT job_id,plugin_id,instance_id,runtime_identity,source_kind,source_release_id,release_digest,state,attempt_count,error_code,created_at,updated_at,completed_at "
                        + "FROM extension_installation_jobs WHERE job_id=?",
                (rs, row) -> new JobView(rs.getObject("job_id", UUID.class), rs.getString("plugin_id"),
                        rs.getObject("instance_id", UUID.class), rs.getString("runtime_identity"), rs.getString("source_kind"),
                        (Long) rs.getObject("source_release_id"), rs.getString("release_digest"), rs.getString("state"),
                        rs.getInt("attempt_count"), rs.getString("error_code"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(), rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()), jobId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Installation job was not found"));
    }

    @Transactional(readOnly = true)
    public List<JobView> list(String pluginId) {
        String sql = "SELECT job_id,plugin_id,instance_id,runtime_identity,source_kind,source_release_id,release_digest,state,attempt_count,error_code,created_at,updated_at,completed_at "
                + "FROM extension_installation_jobs " + (pluginId == null ? "" : "WHERE plugin_id=? ") + "ORDER BY created_at DESC LIMIT 100";
        var mapper = (org.springframework.jdbc.core.RowMapper<JobView>) (rs, row) -> new JobView(
                rs.getObject("job_id", UUID.class), rs.getString("plugin_id"), rs.getObject("instance_id", UUID.class),
                rs.getString("runtime_identity"), rs.getString("source_kind"), (Long) rs.getObject("source_release_id"),
                rs.getString("release_digest"), rs.getString("state"), rs.getInt("attempt_count"), rs.getString("error_code"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());
        return pluginId == null ? jdbc.query(sql, mapper) : jdbc.query(sql, mapper, pluginId);
    }

    @Transactional
    public JobView retry(UUID jobId) {
        Target target = jdbc.query("SELECT plugin_id,instance_id FROM extension_installation_jobs WHERE job_id=? AND state='FAILED'",
                rs -> rs.next() ? new Target(rs.getString("plugin_id"), rs.getObject("instance_id", UUID.class)) : null, jobId);
        if (target == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only failed installation jobs can be retried");
        String subjectLock = "installation:" + target.pluginId() + ":" + (target.instanceId() == null ? "legacy" : target.instanceId());
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> { }, subjectLock);
        Boolean active = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM extension_installation_jobs WHERE plugin_id=? "
                        + "AND instance_id IS NOT DISTINCT FROM ?::uuid AND job_id<>? AND state IN "
                        + "('REQUESTED','POLICY_CHECK','VERIFYING_SOURCE','DOWNLOADING_OR_IMPORTING','VERIFYING_ARTIFACTS','REGISTERING_RELEASE'))",
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

    public record JobView(UUID jobId, String pluginId, UUID instanceId, String runtimeIdentity, String sourceKind,
            Long releaseId, String releaseDigest, String state, int attemptCount, String errorCode,
            java.time.Instant createdAt, java.time.Instant updatedAt, java.time.Instant completedAt) { }
    private record Target(String pluginId, UUID instanceId) { }
}
