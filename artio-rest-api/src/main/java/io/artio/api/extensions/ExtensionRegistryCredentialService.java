package io.artio.api.extensions;

import io.artio.api.security.EncryptionService;
import io.artio.extensions.ExtensionRuntimePort;
import io.artio.extensions.PluginManifest;
import io.artio.extensions.PluginManifestValidator;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Stores credentials only for registry hosts explicitly trusted by Core configuration. */
@Service
public class ExtensionRegistryCredentialService {
  private final JdbcTemplate jdbc;
  private final EncryptionService encryption;
  private final PluginManifestValidator manifestValidator;

  public ExtensionRegistryCredentialService(
      JdbcTemplate jdbc,
      EncryptionService encryption,
      PluginManifestValidator manifestValidator) {
    this.jdbc = jdbc;
    this.encryption = encryption;
    this.manifestValidator = manifestValidator;
  }

  @Transactional
  public RegistryCredentialSummary put(
      String registryHost, String username, String password, UUID actorId) {
    String host = normalizeHost(registryHost);
    if (!manifestValidator.trustsRegistryHost(host))
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Registry host is not in Core's trusted registry allowlist");
    if (username == null || username.isBlank() || username.length() > 256 || containsControl(username))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Registry username is invalid");
    if (password == null || password.isBlank() || password.length() > 2048)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Registry password is invalid");
    jdbc.update(
        "INSERT INTO extension_registry_credentials(registry_host,username,encrypted_password,created_by,updated_by) VALUES (?,?,?,?,?) "
            + "ON CONFLICT(registry_host) DO UPDATE SET username=EXCLUDED.username,encrypted_password=EXCLUDED.encrypted_password,updated_by=EXCLUDED.updated_by,updated_at=now()",
        host,
        username,
        encryption.encrypt(password),
        actorId,
        actorId);
    audit(actorId, host, "REGISTRY_CREDENTIAL_SET");
    return find(host);
  }

  @Transactional(readOnly = true)
  public List<RegistryCredentialSummary> list() {
    return jdbc.query(
        "SELECT registry_host,username,created_at,updated_at FROM extension_registry_credentials ORDER BY registry_host",
        (rs, row) ->
            new RegistryCredentialSummary(
                rs.getString("registry_host"),
                rs.getString("username"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()));
  }

  @Transactional
  public void delete(String registryHost, UUID actorId) {
    String host = normalizeHost(registryHost);
    if (jdbc.update(
            "DELETE FROM extension_registry_credentials WHERE registry_host=?", host)
        == 1) audit(actorId, host, "REGISTRY_CREDENTIAL_DELETE");
  }

  @Transactional(readOnly = true)
  public Map<String, ExtensionRuntimePort.RegistryAuth> credentialsFor(PluginManifest manifest) {
    List<String> images = new ArrayList<>();
    images.add(manifest.image());
    manifest.services().forEach(service -> images.add(service.image()));
    var credentials = new LinkedHashMap<String, ExtensionRuntimePort.RegistryAuth>();
    for (String image : images) {
      int slash = image.indexOf('/');
      if (slash <= 0) continue;
      String host = normalizeHost(image.substring(0, slash));
      if (!manifestValidator.trustsRegistryHost(host))
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Manifest image registry is not trusted by Core");
      jdbc.query(
          "SELECT username,encrypted_password FROM extension_registry_credentials WHERE registry_host=?",
          rs -> {
            if (rs.next())
              credentials.put(
                  host,
                  new ExtensionRuntimePort.RegistryAuth(
                      rs.getString("username"), encryption.decrypt(rs.getString("encrypted_password"))));
            return null;
          },
          host);
    }
    return Map.copyOf(credentials);
  }

  private RegistryCredentialSummary find(String host) {
    return jdbc.query(
            "SELECT registry_host,username,created_at,updated_at FROM extension_registry_credentials WHERE registry_host=?",
            (rs, row) ->
                new RegistryCredentialSummary(
                    rs.getString("registry_host"),
                    rs.getString("username"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()),
            host)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Saved registry credential was not found"));
  }

  private void audit(UUID actorId, String host, String action) {
    try {
      jdbc.update(
          "INSERT INTO extension_audit_events(id,plugin_id,actor_user_id,action,details) VALUES (?, 'custom.registry', ?, ?, ?::jsonb)",
          UUID.randomUUID(),
          actorId,
          action,
          "{\"registryHost\":\"" + host + "\"}");
    } catch (org.springframework.dao.DataAccessException failure) {
      throw new IllegalStateException("Registry credential audit could not be recorded", failure);
    }
  }

  private static String normalizeHost(String value) {
    if (value == null || value.isBlank() || value.length() > 255 || containsControl(value))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Registry host is invalid");
    String candidate = value.trim().toLowerCase(java.util.Locale.ROOT);
    if (!candidate.matches("[a-z0-9](?:[a-z0-9.-]{0,249})(?::[0-9]{1,5})?"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Registry host must be a host and optional port");
    if (candidate.contains("..") || candidate.endsWith("."))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Registry host is invalid");
    try {
      URI uri = URI.create("https://" + candidate);
      if (uri.getHost() == null
          || uri.getRawUserInfo() != null
          || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()))
        throw new IllegalArgumentException();
      if (uri.getPort() > 65535) throw new IllegalArgumentException();
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Registry host is invalid", invalid);
    }
    return candidate;
  }

  private static boolean containsControl(String value) {
    return value.chars().anyMatch(Character::isISOControl);
  }

  public record RegistryCredentialSummary(
      String registryHost,
      String username,
      java.time.Instant createdAt,
      java.time.Instant updatedAt) {}
}
