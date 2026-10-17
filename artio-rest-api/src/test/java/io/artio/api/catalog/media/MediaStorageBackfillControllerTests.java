package io.artio.api.catalog.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class MediaStorageBackfillControllerTests {
    private final MediaStorageBackfillService service = mock(MediaStorageBackfillService.class);
    private final MediaStorageBackfillController controller = new MediaStorageBackfillController(service);

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

    private static JwtAuthenticationToken auth(String role) {
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "none").claim("role", role).build();
        return new JwtAuthenticationToken(jwt);
    }
}
