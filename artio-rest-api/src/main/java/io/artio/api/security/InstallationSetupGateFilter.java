package io.artio.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.error.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/** Keeps commerce APIs unavailable until the first system owner completes setup. */
final class InstallationSetupGateFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    InstallationSetupGateFilter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || path.startsWith("/api/setup/") || path.startsWith("/api/v1/setup/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean completed = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM users WHERE role IN ('ADMIN','SUPERADMIN'))", Boolean.class));
        if (completed) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType("application/json");
        response.setHeader("Retry-After", "30");
        mapper.writeValue(response.getOutputStream(), new ApiError(Instant.now(), HttpStatus.SERVICE_UNAVAILABLE.value(),
                "Service Unavailable", ApiError.codeFor(HttpStatus.SERVICE_UNAVAILABLE.value()),
                "Initial setup is required", request.getRequestURI()));
    }
}
