package io.artio.api.extensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.extensions.ExtensionRuntimePort;
import io.artio.extensions.PluginManifest;
import io.artio.extensions.PluginManifestV2;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Locally stops any runtime target whose exact OCI digest has been banned by verified policy. */
@Component
@ConditionalOnProperty(prefix = "artio.workers", name = "enabled", havingValue = "true")
public final class ExtensionSecurityContainmentWorker {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final ObjectProvider<ExtensionRuntimePort> runtimeProvider;

  public ExtensionSecurityContainmentWorker(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      ObjectProvider<ExtensionRuntimePort> runtimeProvider) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.runtimeProvider = runtimeProvider;
  }

  @Scheduled(fixedDelayString = "${artio.extensions.security.reconcile-interval-ms:15000}")
  public void reconcile() {
    List<Target> targets = new java.util.ArrayList<>();
    targets.addAll(
        jdbc.query(
            "SELECT plugin_id,manifest::text,('legacy:'||plugin_id) AS runtime_identity FROM extension_plugins WHERE lifecycle_state<>'UNINSTALLED' ORDER BY plugin_id",
            (rs, row) ->
                new Target(
                    rs.getString("plugin_id"),
                    null,
                    rs.getString("runtime_identity"),
                    read(rs.getString("manifest"), PluginManifest.class),
                    false)));
    targets.addAll(
        jdbc.query(
            "SELECT i.plugin_id,i.instance_id,i.runtime_identity,i.compatibility_mode,r.manifest::text AS manifest,v.manifest::text AS v2_manifest "
                + "FROM extension_plugin_instances i LEFT JOIN extension_plugin_releases r ON r.release_id=i.release_id "
                + "LEFT JOIN extension_plugin_v2_releases v ON v.release_id=i.release_id "
                + "WHERE i.lifecycle_state<>'UNINSTALLED' AND i.compatibility_mode<>'LEGACY_V1' ORDER BY i.plugin_id,i.instance_key",
            (rs, row) -> {
              PluginManifest manifest;
              if ("CAPABILITY_V2".equals(rs.getString("compatibility_mode"))) {
                PluginManifestV2 v2 = read(rs.getString("v2_manifest"), PluginManifestV2.class);
                manifest = v2.packageManifest();
              } else {
                manifest = read(rs.getString("manifest"), PluginManifest.class);
              }
              return new Target(
                  rs.getString("plugin_id"),
                  rs.getObject("instance_id", UUID.class),
                  rs.getString("runtime_identity"),
                  manifest,
                  true);
            }));
    for (Target target : targets) {
      List<String> banned = ExtensionSecurityPolicy.bannedDigests(jdbc, target.manifest());
      if (!banned.isEmpty()) contain(target, banned);
    }
  }

  private void contain(Target target, List<String> bannedDigests) {
    upsert(target, bannedDigests, "STOP_FAILED", "CONTAINMENT_PENDING", null);
    try {
      ExtensionRuntimePort runtime = runtimeProvider.getIfAvailable();
      if (runtime == null)
        throw new ExtensionRuntimePort.ExtensionUnavailableException("Extension runtime is unavailable");
      if (target.instance()) runtime.disableInstance(target.runtimeIdentity(), target.pluginId());
      else runtime.disable(target.pluginId());
      upsert(target, bannedDigests, "STOPPED", null, java.time.Instant.now());
    } catch (Exception failure) {
      upsert(target, bannedDigests, "STOP_FAILED", safeCode(failure), null);
    }
  }

  private void upsert(
      Target target,
      List<String> banned,
      String state,
      String error,
      java.time.Instant stoppedAt) {
    String digestList;
    try {
      digestList = mapper.writeValueAsString(banned);
    } catch (Exception failure) {
      throw new IllegalStateException("Security containment digests could not be serialized", failure);
    }
    jdbc.update(
        "INSERT INTO extension_security_blocked_targets(runtime_identity,plugin_id,instance_id,banned_image_digests,containment_status,last_error_code,last_attempt_at,stopped_at) "
            + "VALUES (?,?,?,?::jsonb,?,?,now(),?) ON CONFLICT(runtime_identity) DO UPDATE SET "
            + "plugin_id=EXCLUDED.plugin_id,instance_id=EXCLUDED.instance_id,banned_image_digests=EXCLUDED.banned_image_digests,"
            + "containment_status=EXCLUDED.containment_status,last_error_code=EXCLUDED.last_error_code,last_attempt_at=now(),stopped_at=EXCLUDED.stopped_at",
        target.runtimeIdentity(),
        target.pluginId(),
        target.instanceId(),
        digestList,
        state,
        error,
        stoppedAt == null ? null : java.sql.Timestamp.from(stoppedAt));
  }

  private <T> T read(String json, Class<T> type) {
    try {
      return mapper.readValue(json, type);
    } catch (Exception failure) {
      throw new IllegalStateException("Installed extension manifest is invalid during security reconciliation", failure);
    }
  }

  private static String safeCode(Exception failure) {
    String code = failure.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", "");
    return code.isBlank() ? "CONTAINMENT_FAILED" : code.substring(0, Math.min(64, code.length()));
  }

  private record Target(
      String pluginId,
      UUID instanceId,
      String runtimeIdentity,
      PluginManifest manifest,
      boolean instance) {}
}
