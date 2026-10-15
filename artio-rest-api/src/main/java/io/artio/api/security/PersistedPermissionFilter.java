package io.artio.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.error.ApiErrorWriter;
import io.artio.users.Permission;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Resolves administrator role and permissions from the current user row for every authenticated request. */
public final class PersistedPermissionFilter extends OncePerRequestFilter {
    private static final String USER_ACCESS_SQL = "SELECT role,permissions,active FROM users WHERE id=?";
    private static final Set<String> KNOWN_ROLES = Set.of("CUSTOMER", "ADMIN", "SUPERADMIN");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PersistedPermissionFilter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt)) {
            chain.doFilter(request, response);
            return;
        }

        final UUID userId;
        try {
            userId = UUID.fromString(jwt.getToken().getSubject());
        } catch (IllegalArgumentException exception) {
            reject(request, response, HttpStatus.UNAUTHORIZED, "The authenticated user is no longer available");
            return;
        }

        List<java.util.Map<String, Object>> users = jdbc.queryForList(USER_ACCESS_SQL, userId);
        if (users.isEmpty()) {
            reject(request, response, HttpStatus.UNAUTHORIZED, "The authenticated user is no longer available");
            return;
        }

        var user = users.getFirst();
        UserAccess access = new UserAccess(String.valueOf(user.get("role")),
                user.get("permissions") == null ? "" : String.valueOf(user.get("permissions")),
                Boolean.TRUE.equals(user.get("active")));
        if (!access.active() || !KNOWN_ROLES.contains(access.role())) {
            reject(request, response, HttpStatus.UNAUTHORIZED, "The authenticated user is no longer available");
            return;
        }

        List<GrantedAuthority> authorities = new ArrayList<>();
        jwt.getAuthorities().stream()
                .filter(authority -> !authority.getAuthority().startsWith("ROLE_")
                        && !authority.getAuthority().startsWith("PERMISSION_"))
                .forEach(authorities::add);
        authorities.add(new SimpleGrantedAuthority("ROLE_" + access.role()));
        final Set<Permission> permissions;
        try {
            permissions = effectivePermissions(access);
        } catch (IllegalArgumentException exception) {
            reject(request, response, HttpStatus.FORBIDDEN, "The account has an invalid permission assignment");
            return;
        }
        for (Permission permission : permissions) {
            authorities.add(new SimpleGrantedAuthority("PERMISSION_" + permission.name()));
        }
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt.getToken(), authorities, jwt.getName()));
        chain.doFilter(request, response);
    }

    private static Set<Permission> effectivePermissions(UserAccess access) {
        if ("SUPERADMIN".equals(access.role())) return EnumSet.allOf(Permission.class);
        if (!"ADMIN".equals(access.role())) return EnumSet.noneOf(Permission.class);

        String raw = access.permissions() == null ? "" : access.permissions().trim();
        if (raw.isEmpty() || "*".equals(raw)) return EnumSet.allOf(Permission.class); // Empty is the legacy full-access representation.
        if ("NONE".equalsIgnoreCase(raw)) return EnumSet.noneOf(Permission.class);

        EnumSet<Permission> permissions = EnumSet.noneOf(Permission.class);
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> Permission.valueOf(value.toUpperCase(Locale.ROOT)))
                .forEach(permissions::add);
        return permissions;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        SecurityContextHolder.clearContext();
        ApiErrorWriter.write(mapper, request, response, status, message);
    }

    private record UserAccess(String role, String permissions, boolean active) { }
}
