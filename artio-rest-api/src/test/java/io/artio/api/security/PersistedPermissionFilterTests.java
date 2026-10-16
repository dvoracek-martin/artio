package io.artio.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class PersistedPermissionFilterTests {
    private final UUID userId = UUID.randomUUID();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PersistedPermissionFilter filter = new PersistedPermissionFilter(jdbc,
            new ObjectMapper().findAndRegisterModules());

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void replacesStaleTokenRoleAndAddsOnlyCurrentDatabasePermissions() throws Exception {
        authenticate("SUPERADMIN");
        when(jdbc.queryForList(anyString(), eq(userId))).thenReturn(List.of(
                Map.of("role", "ADMIN", "permissions", "REPORTS_VIEW", "active", true)));
        var request = new MockHttpServletRequest("GET", "/api/v1/admin/reports");
        var response = new MockHttpServletResponse();
        var continued = new AtomicBoolean();
        var observedAuthorities = new AtomicReference<List<String>>();

        filter.doFilter(request, response, (req, res) -> {
            continued.set(true);
            observedAuthorities.set(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority).toList());
        });

        assertThat(continued).isTrue();
        assertThat(observedAuthorities.get()).contains("ROLE_ADMIN", "PERMISSION_REPORTS_VIEW")
                .doesNotContain("ROLE_SUPERADMIN", "PERMISSION_USER_MANAGE");
    }

    @Test void inactiveUserCannotContinueWithAnAlreadyIssuedToken() throws Exception {
        authenticate("ADMIN");
        when(jdbc.queryForList(anyString(), eq(userId))).thenReturn(List.of(
                Map.of("role", "ADMIN", "permissions", "*", "active", false)));
        var request = new MockHttpServletRequest("GET", "/api/v1/admin/reports");
        var response = new MockHttpServletResponse();
        var continued = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> continued.set(true));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("authenticated user is no longer available");
        assertThat(continued).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test void explicitNoPermissionsDoesNotGrantLegacyFullAccess() throws Exception {
        authenticate("ADMIN");
        when(jdbc.queryForList(anyString(), eq(userId))).thenReturn(List.of(
                Map.of("role", "ADMIN", "permissions", "NONE", "active", true)));
        var request = new MockHttpServletRequest("GET", "/api/v1/admin/reports");
        var response = new MockHttpServletResponse();
        var authorities = new AtomicReference<List<String>>();

        filter.doFilter(request, response, (req, res) -> authorities.set(
                SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority).toList()));

        assertThat(authorities.get()).containsExactly("ROLE_ADMIN");
    }

    @Test void resolvesDedicatedExtensionPermissionsFromPersistedAccess() throws Exception {
        authenticate("ADMIN");
        when(jdbc.queryForList(anyString(), eq(userId))).thenReturn(List.of(
                Map.of("role", "ADMIN", "permissions", "EXTENSION_MANAGE,EXTENSION_ROUTING_VIEW,EXTENSION_OPERATIONS_MANAGE", "active", true)));
        var authorities = new AtomicReference<List<String>>();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/admin/extensions"), new MockHttpServletResponse(),
                (req, res) -> authorities.set(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority).toList()));

        assertThat(authorities.get()).contains("PERMISSION_EXTENSION_MANAGE", "PERMISSION_EXTENSION_ROUTING_VIEW",
                "PERMISSION_EXTENSION_OPERATIONS_MANAGE").doesNotContain("PERMISSION_EXTENSION_ROUTING_MANAGE",
                        "PERMISSION_EXTENSION_OPERATIONS_VIEW");
    }

    private void authenticate(String tokenRole) {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none").subject(userId.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).claim("role", tokenRole).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_" + tokenRole))));
    }
}
