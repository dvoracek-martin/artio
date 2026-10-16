package io.artio.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.error.ApiErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Limits a privileged first-login token to completing its mandatory MFA enrollment. */
public final class PrivilegedMfaEnrollmentFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;

    public PrivilegedMfaEnrollmentFilter(ObjectMapper mapper) { this.mapper = mapper; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt
                && Boolean.TRUE.equals(jwt.getToken().getClaimAsBoolean("mfaSetupOnly"))
                && !isEnrollmentRoute(request.getRequestURI())) {
            ApiErrorWriter.write(mapper, request, response, HttpStatus.FORBIDDEN,
                    "Privileged access requires verified multi-factor authentication enrollment");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isEnrollmentRoute(String path) {
        return path.equals("/api/v1/auth/mfa/totp/enrollment") || path.equals("/api/v1/auth/mfa/totp/verify")
                || path.equals("/api/v1/auth/mfa/totp/enrollment") || path.equals("/api/v1/auth/mfa/totp/verify");
    }
}
