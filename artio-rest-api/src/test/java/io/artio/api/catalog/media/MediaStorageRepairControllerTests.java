package io.artio.api.catalog.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class MediaStorageRepairControllerTests {
    private final MediaStorageRepairService service = mock(MediaStorageRepairService.class);
    private final MediaStorageRepairController controller = new MediaStorageRepairController(service);

    @Test
    void onlySuperadminCanStartStorageRepair() {
        assertThatThrownBy(() -> controller.repair(null, 50, UUID.randomUUID(), auth("ADMIN")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode().value()).isEqualTo(403));
        verifyNoInteractions(service);
    }

    @Test
    void superadminUsesTheBoundedRepairServiceAndRunIdentity() {
        UUID runId = UUID.randomUUID();
        var expected = new MediaStorageRepairService.RepairResult(0, 0, 0, true, null, List.of());
        when(service.repair(null, 50, runId)).thenReturn(expected);

        assertThat(controller.repair(null, 50, runId, auth("SUPERADMIN"))).isEqualTo(expected);

        verify(service).repair(null, 50, runId);
    }

    private static JwtAuthenticationToken auth(String role) {
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "none").claim("role", role).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
