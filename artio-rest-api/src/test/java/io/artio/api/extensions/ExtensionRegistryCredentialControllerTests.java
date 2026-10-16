package io.artio.api.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class ExtensionRegistryCredentialControllerTests {
  private final ExtensionRegistryCredentialService credentials =
      mock(ExtensionRegistryCredentialService.class);
  private final ExtensionRegistryCredentialController controller =
      new ExtensionRegistryCredentialController(credentials);

  @Test
  void listsCredentialsForRecentlyMfaVerifiedToken() {
    var summary = new ExtensionRegistryCredentialService.RegistryCredentialSummary(
        "registry.example", "publisher", Instant.now(), Instant.now());
    when(credentials.list()).thenReturn(List.of(summary));

    assertThat(controller.list(actor(true, Instant.now()))).containsExactly(summary);
    verify(credentials).list();
  }

  @Test
  void rejectsMissingMfaAndStaleStepUpBeforeAccessingCredentials() {
    assertThatThrownBy(() -> controller.list(actor(false, Instant.now())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Recent multi-factor authentication is required");
    assertThatThrownBy(() -> controller.list(actor(true, Instant.now().minusSeconds(301))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Recent multi-factor authentication is required");
    verifyNoInteractions(credentials);
  }

  private static JwtAuthenticationToken actor(boolean mfaVerified, Instant issuedAt) {
    Jwt jwt = Jwt.withTokenValue("test-token")
        .header("alg", "none")
        .subject("20000000-0000-0000-0000-000000000001")
        .issuedAt(issuedAt)
        .expiresAt(issuedAt.plusSeconds(3600))
        .claim("mfaVerified", mfaVerified)
        .build();
    return new JwtAuthenticationToken(jwt);
  }
}
