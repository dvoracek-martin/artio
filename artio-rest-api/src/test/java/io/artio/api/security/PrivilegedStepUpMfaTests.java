package io.artio.api.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class PrivilegedStepUpMfaTests {
    private final PrivilegedStepUpMfa guard = new PrivilegedStepUpMfa(true);

    @Test
    void rejectsMissingUnverifiedAndStaleStepUpTokens() {
        assertThatThrownBy(() -> guard.require(null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> guard.require(token(false, Instant.now()))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> guard.require(token(true, Instant.now().minusSeconds(301))))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void allowsRecentVerifiedMfaAndDevelopmentExemption() {
        guard.require(token(true, Instant.now()));
        new PrivilegedStepUpMfa(false).require(token(false, Instant.now().minusSeconds(86_400)));
    }

    private static JwtAuthenticationToken token(boolean verified, Instant issuedAt) {
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "none").issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(3600)).claim("mfaVerified", verified).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_SUPERADMIN")));
    }
}
