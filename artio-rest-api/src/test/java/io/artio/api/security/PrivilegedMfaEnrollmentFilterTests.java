package io.artio.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class PrivilegedMfaEnrollmentFilterTests {
    private final PrivilegedMfaEnrollmentFilter filter = new PrivilegedMfaEnrollmentFilter(new ObjectMapper().findAndRegisterModules());

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void setupOnlyPrivilegedTokenCannotCallCommerceRoutes() throws Exception {
        authenticate(true);
        var request = new MockHttpServletRequest("GET", "/api/v1/admin/system/settings");
        var response = new MockHttpServletResponse();
        var continued = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> continued.set(true));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("verified multi-factor authentication");
        assertThat(continued).isFalse();
    }

    @Test void setupOnlyPrivilegedTokenCanCompleteEnrollment() throws Exception {
        authenticate(true);
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/mfa/totp/enrollment");
        var response = new MockHttpServletResponse();
        var continued = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> continued.set(true));

        assertThat(continued).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test void verifiedPrivilegedTokenContinuesNormally() throws Exception {
        authenticate(false);
        var request = new MockHttpServletRequest("GET", "/api/v1/admin/system/settings");
        var response = new MockHttpServletResponse();
        var continued = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> continued.set(true));

        assertThat(continued).isTrue();
    }

    private void authenticate(boolean setupOnly) {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none").subject("00000000-0000-0000-0000-000000000001")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).claim("role", "SUPERADMIN")
                .claim("mfaSetupOnly", setupOnly).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_SUPERADMIN"))));
    }
}
