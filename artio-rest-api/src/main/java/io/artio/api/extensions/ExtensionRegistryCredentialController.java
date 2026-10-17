package io.artio.api.extensions;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.artio.api.security.PrivilegedStepUpMfa;
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
  private final ExtensionRegistryCredentialService credentials;
  private final PrivilegedStepUpMfa stepUpMfa;

  public ExtensionRegistryCredentialController(ExtensionRegistryCredentialService credentials, PrivilegedStepUpMfa stepUpMfa) {
    this.credentials = credentials;
    this.stepUpMfa = stepUpMfa;
  }

  @GetMapping
  public List<ExtensionRegistryCredentialService.RegistryCredentialSummary> list(
      JwtAuthenticationToken actor) {
    requireSuperadmin(actor);
    stepUpMfa.require(actor);
    return credentials.list();
  }

  @PutMapping("/{registryHost}")
  public ExtensionRegistryCredentialService.RegistryCredentialSummary put(
      @PathVariable String registryHost,
      @RequestBody RegistryCredentialRequest request,
      JwtAuthenticationToken actor) {
    requireSuperadmin(actor);
    stepUpMfa.require(actor);
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
    requireSuperadmin(actor);
    stepUpMfa.require(actor);
    credentials.delete(registryHost, UUID.fromString(actor.getToken().getSubject()));
    return Map.of("message", "Registry credentials removed");
  }

  private static void requireSuperadmin(JwtAuthenticationToken actor) {
    if (actor == null || actor.getAuthorities().stream().noneMatch(authority -> "ROLE_SUPERADMIN".equals(authority.getAuthority())))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SUPERADMIN authority is required to manage private registry credentials");
  }

  public record RegistryCredentialRequest(String username, String password) {}
}
