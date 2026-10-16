package io.artio.api.extensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.extensions.PluginManifest;
import io.artio.extensions.PluginManifestValidator;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Protected upload path for customer-supplied OCI archives; Docker access remains in the controller runtime. */
@RestController
@RequestMapping("/api/v1/admin/extensions")
public class LocalExtensionArchiveUploadController {
  private static final Duration STEP_UP_AGE = Duration.ofMinutes(5);
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final PluginManifestValidator manifestValidator;
  private final ExtensionInstallationJobOperations installationJobs;
  private final OciArchiveQuarantineStore quarantine;
  private final String coreVersion;

  public LocalExtensionArchiveUploadController(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      PluginManifestValidator manifestValidator,
      ExtensionInstallationJobOperations installationJobs,
      OciArchiveQuarantineStore quarantine,
      @Value("${artio.extensions.core-version:0.1.0}") String coreVersion) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.manifestValidator = manifestValidator;
    this.installationJobs = installationJobs;
    this.quarantine = quarantine;
    this.coreVersion = coreVersion;
  }

  @PostMapping(path = "/{pluginId}/installations/archive", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
  public ResponseEntity<ExtensionInstallationJobOperations.JobView> upload(
      @PathVariable String pluginId,
      @RequestPart("archive") MultipartFile archive,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      JwtAuthenticationToken actor) {
    requireStepUpMfa(actor);
    if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{8,128}")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must contain 8 to 128 safe characters");
    }
    PluginRow plugin = jdbc.query("SELECT manifest::text,lifecycle_state FROM extension_plugins WHERE plugin_id=?",
        rs -> rs.next() ? new PluginRow(readManifest(rs.getString(1)), rs.getString(2)) : null, pluginId);
    if (plugin == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plugin was not found");
    if (!java.util.Set.of("CONFIGURED", "INSTALLED", "DISABLED", "FAILED").contains(plugin.lifecycleState())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Plugin must be configured or stopped before installation");
    }
    var errors = manifestValidator.validate(plugin.manifest(), coreVersion, java.util.Set.of(pluginId));
    if (!errors.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("; ", errors));
    ExtensionSecurityPolicy.requireAllowed(jdbc, plugin.manifest());

    OciArchiveQuarantineStore.StoredArchive stored = null;
    boolean attached = false;
    try {
      stored = quarantine.store(archive);
      UUID actorId = UUID.fromString(actor.getToken().getSubject());
      var job = installationJobs.requestArchive(pluginId, null, "legacy:" + pluginId, 0,
          plugin.manifest(), actorId, idempotencyKey, stored.path().getFileName().toString(),
          stored.sha256(), stored.size());
      attached = true;
      String persistedName = jdbc.queryForObject(
          "SELECT quarantine_name FROM extension_local_installation_archives WHERE job_id=?", String.class, job.jobId());
      if (!stored.path().getFileName().toString().equals(persistedName)) quarantine.delete(stored.path());
      return ResponseEntity.accepted().body(job);
    } catch (ResponseStatusException known) {
      throw known;
    } catch (Exception failure) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Local plugin archive could not be queued", failure);
    } finally {
      if (!attached && stored != null) {
        try { quarantine.delete(stored.path()); }
        catch (java.io.IOException cleanupFailure) {
          throw new IllegalStateException("Could not remove an unqueued OCI archive", cleanupFailure);
        }
      }
    }
  }

  private PluginManifest readManifest(String source) {
    try { return mapper.readValue(source, PluginManifest.class); }
    catch (Exception malformed) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Stored plugin manifest is invalid", malformed);
    }
  }

  private static void requireStepUpMfa(JwtAuthenticationToken actor) {
    Instant issuedAt = actor.getToken().getIssuedAt();
    if (!Boolean.TRUE.equals(actor.getToken().getClaimAsBoolean("mfaVerified")) || issuedAt == null
        || issuedAt.isBefore(Instant.now().minus(STEP_UP_AGE))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Recent multi-factor authentication is required");
    }
  }

  private record PluginRow(PluginManifest manifest, String lifecycleState) {}
}
