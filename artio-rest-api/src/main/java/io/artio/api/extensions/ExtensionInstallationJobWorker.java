package io.artio.api.extensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.artio.extensions.ExtensionRuntimePort;
import io.artio.extensions.PluginManifest;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Executes durable install jobs outside the request thread and without holding a database transaction over Docker I/O. */
@Component
@ConditionalOnProperty(prefix = "artio.workers", name = "enabled", havingValue = "true")
public class ExtensionInstallationJobWorker {
    private static final TypeReference<List<java.util.Map<String, String>>> IMAGE_LIST = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ObjectProvider<ExtensionRuntimePort> runtimeProvider;

    public ExtensionInstallationJobWorker(JdbcTemplate jdbc, ObjectMapper mapper,
            ObjectProvider<ExtensionRuntimePort> runtimeProvider) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.runtimeProvider = runtimeProvider;
    }

    @Scheduled(fixedDelayString = "${artio.extensions.installations.poll-interval-ms:1000}")
    public void runPending() {
        List<InstallJob> jobs = jdbc.query("""
                UPDATE extension_installation_jobs SET state='POLICY_CHECK',attempt_count=attempt_count+1,
                    lease_token=md5(job_id::text || clock_timestamp()::text || random()::text)::uuid,
                    lease_until=now()+interval '30 minutes',updated_at=now()
                WHERE job_id IN (
                    SELECT job_id FROM extension_installation_jobs
                    WHERE state='REQUESTED' OR (state IN ('POLICY_CHECK','VERIFYING_SOURCE','DOWNLOADING_OR_IMPORTING','VERIFYING_ARTIFACTS','REGISTERING_RELEASE') AND lease_until<now())
                    ORDER BY created_at LIMIT 4 FOR UPDATE SKIP LOCKED
                )
                RETURNING job_id,plugin_id,instance_id,runtime_identity,manifest_snapshot::text,permission_snapshot::text,image_snapshot::text,lease_token
                """, (rs, row) -> new InstallJob(rs.getObject("job_id", UUID.class), rs.getString("plugin_id"),
                rs.getObject("instance_id", UUID.class), rs.getString("runtime_identity"), rs.getString("manifest_snapshot"),
                rs.getString("permission_snapshot"), rs.getString("image_snapshot"), rs.getObject("lease_token", UUID.class)));
        jobs.forEach(this::install);
    }

    private void install(InstallJob job) {
        PluginManifest manifest = null;
        try {
            manifest = mapper.readValue(job.manifestJson(), PluginManifest.class);
            if (!job.pluginId().equals(manifest.id())) throw new IllegalStateException("Installation manifest identity changed");
            ExtensionSecurityPolicy.requireAllowed(jdbc, manifest);
            ExtensionRuntimePort runtime = runtimeProvider.getIfAvailable();
            if (runtime == null) throw new ExtensionRuntimePort.ExtensionUnavailableException("Plugin runtime is unavailable");
            if (!transition(job, "VERIFYING_SOURCE")) return;
            List<String> permissionSnapshot = mapper.readValue(job.permissionJson(), PermissionSnapshot.class).permissions();
            List<java.util.Map<String, String>> imageSnapshot = mapper.readValue(job.imageJson(), IMAGE_LIST);
            if (!permissionSnapshot.equals(manifest.permissions())) throw new IllegalStateException("Installation permission snapshot changed");
            var expectedImages = new java.util.ArrayList<java.util.Map<String, String>>();
            expectedImages.add(java.util.Map.of("id", "primary", "image", manifest.image()));
            manifest.services().forEach(service -> expectedImages.add(java.util.Map.of("id", service.id(), "image", service.image())));
            if (!imageSnapshot.equals(expectedImages) || expectedImages.stream().anyMatch(image ->
                    !image.get("image").matches(".+@sha256:[a-f0-9]{64}"))) {
                throw new IllegalStateException("Installation image snapshot is invalid or changed");
            }
            if (!transition(job, "DOWNLOADING_OR_IMPORTING")) return;
            if (job.instanceId() == null) runtime.install(manifest);
            else runtime.installInstance(job.runtimeIdentity(), manifest);
            if (!transition(job, "VERIFYING_ARTIFACTS") || !transition(job, "REGISTERING_RELEASE")) return;
            if (job.instanceId() == null) {
                jdbc.update("UPDATE extension_plugins SET lifecycle_state='INSTALLED',updated_at=now() WHERE plugin_id=?", job.pluginId());
                jdbc.update("UPDATE extension_plugin_instances SET lifecycle_state='INSTALLED',updated_at=now() "
                        + "WHERE plugin_id=? AND compatibility_mode='LEGACY_V1' AND lifecycle_state IN ('CONFIGURED','FAILED')", job.pluginId());
            } else {
                jdbc.update("UPDATE extension_plugin_instances SET lifecycle_state='INSTALLED',updated_at=now() "
                        + "WHERE instance_id=? AND lifecycle_state IN ('CONFIGURED','FAILED')", job.instanceId());
            }
            jdbc.update("UPDATE extension_installation_jobs SET state='INSTALLED',lease_token=NULL,lease_until=NULL,error_code=NULL,"
                            + "completed_at=now(),updated_at=now() WHERE job_id=? AND lease_token=?",
                    job.jobId(), job.leaseToken());
        } catch (ExtensionSecurityPolicy.BannedArtifactException blocked) {
            jdbc.update("UPDATE extension_installation_jobs SET state='SECURITY_BLOCKED',lease_token=NULL,lease_until=NULL,error_code='MARKETPLACE_SECURITY_ADVISORY',"
                            + "completed_at=now(),updated_at=now() WHERE job_id=? AND lease_token=?",
                    job.jobId(), job.leaseToken());
        } catch (Exception failure) {
            String error = failure.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", "");
            if (error.length() > 64) error = error.substring(0, 64);
            int owned = jdbc.update("UPDATE extension_installation_jobs SET state='FAILED',lease_token=NULL,lease_until=NULL,error_code=?,"
                            + "completed_at=now(),updated_at=now() WHERE job_id=? AND lease_token=?",
                    error, job.jobId(), job.leaseToken());
            if (owned != 1) return;
            if (job.instanceId() == null) {
                jdbc.update("UPDATE extension_plugins SET lifecycle_state='FAILED',updated_at=now() WHERE plugin_id=?", job.pluginId());
                jdbc.update("UPDATE extension_plugin_instances SET lifecycle_state='FAILED',updated_at=now() "
                        + "WHERE plugin_id=? AND compatibility_mode='LEGACY_V1'", job.pluginId());
            } else {
                jdbc.update("UPDATE extension_plugin_instances SET lifecycle_state='FAILED',updated_at=now() WHERE instance_id=?", job.instanceId());
            }
        }
    }

    private boolean transition(InstallJob job, String state) {
        return jdbc.update("UPDATE extension_installation_jobs SET state=?,updated_at=now() WHERE job_id=? AND lease_token=? AND lease_until>now()",
                state, job.jobId(), job.leaseToken()) == 1;
    }

    private record InstallJob(UUID jobId, String pluginId, UUID instanceId, String runtimeIdentity,
            String manifestJson, String permissionJson, String imageJson, UUID leaseToken) { }
    private record PermissionSnapshot(List<String> permissions) { }
}
