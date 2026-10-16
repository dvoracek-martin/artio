package io.artio.api.extensions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Superadmin, recent-MFA management of explicitly trusted private OCI registry credentials. */
@RestController
@RequestMapping("/api/v1/admin/extensions/registries")
public final class ExtensionRegistryCredentialController {
  private static final Duration STEP_UP_AGE = Duration.ofMinutes(5);
  private final ExtensionRegistryCredentialService credentials;

  public ExtensionRegistryCredentialController(ExtensionRegistryCredentialService credentials) {
    this.credentials = credentials;
  }

  @GetMapping
  public List<ExtensionRegistryCredentialService.RegistryCredentialSummary> list(
      JwtAuthenticationToken actor) {
    requireStepUpMfa(actor);
    return credentials.list();
  }

  @PutMapping("/{registryHost}")
  public ExtensionRegistryCredentialService.RegistryCredentialSummary put(
      @PathVariable String registryHost,
      @RequestBody RegistryCredentialRequest request,
      JwtAuthenticationToken actor) {
    requireStepUpMfa(actor);
    if (request == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Registry credentials are required");
    return credentials.put(
        registryHost,
        request.username(),
        request.password(),
        UUID.fromString(actor.getToken().getSubject()));
  }

  @DeleteMapping("/{registryHost}")
  public Map<String, String> delete(
      @PathVariable String registryHost, JwtAuthenticationToken actor) {
    requireStepUpMfa(actor);
    credentials.delete(registryHost, UUID.fromString(actor.getToken().getSubject()));
    return Map.of("message", "Registry credentials removed");
  }

  private static void requireStepUpMfa(JwtAuthenticationToken actor) {
    Instant issuedAt = actor.getToken().getIssuedAt();
    if (!Boolean.TRUE.equals(actor.getToken().getClaimAsBoolean("mfaVerified"))
        || issuedAt == null
        || issuedAt.isBefore(Instant.now().minus(STEP_UP_AGE)))
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Recent multi-factor authentication is required");
  }

  public record RegistryCredentialRequest(String username, String password) {}
}
