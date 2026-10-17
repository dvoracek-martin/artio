package io.artio.api.catalog.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.artio.api.security.PrivilegedStepUpMfa;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

class MediaStorageBackfillControllerTests {
    private final MediaStorageBackfillService service = mock(MediaStorageBackfillService.class);
    private final MediaStorageBackfillController controller = new MediaStorageBackfillController(service, new PrivilegedStepUpMfa(true));

    @Test
    void onlySuperadminCanStartStorageMigration() {
        assertThatThrownBy(() -> controller.backfill(null, 50, auth("ADMIN")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode().value()).isEqualTo(403));
        verifyNoInteractions(service);
    }

    @Test
    void superadminUsesTheBoundedBackfillService() {
        var expected = new MediaStorageBackfillService.BackfillResult(0, 0, 0, true, null, List.of());
        when(service.backfill(null, 50)).thenReturn(expected);

        assertThat(controller.backfill(null, 50, auth("SUPERADMIN"))).isEqualTo(expected);

        verify(service).backfill(null, 50);
    }

    @Test
    void backfillRequiresRecentMfaBeforeCallingStorage() {
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "none").claim("role", "SUPERADMIN")
                .issuedAt(java.time.Instant.now()).expiresAt(java.time.Instant.now().plusSeconds(300))
                .claim("mfaVerified", false).build();
        var unverified = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_SUPERADMIN")));

        assertThatThrownBy(() -> controller.backfill(null, 50, unverified))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode().value()).isEqualTo(403));
        verifyNoInteractions(service);
    }

    private static JwtAuthenticationToken auth(String role) {
        var now = java.time.Instant.now();
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "none").issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim("mfaVerified", true).claim("role", role).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
