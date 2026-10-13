package io.artio.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Resolves an authenticated tenant from signed claims and verifies every selected membership. */
public final class TenantContextFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    public TenantContextFilter(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        try {
            Authentication authentication=SecurityContextHolder.getContext().getAuthentication();
            if(authentication instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated()) {
                UUID userId=UUID.fromString(jwt.getToken().getSubject());
                String header=request.getHeader("X-Artio-Tenant");
                UUID tenantId;
                try {
                    tenantId=header==null||header.isBlank()?UUID.fromString(jwt.getToken().getClaimAsString("tenant_id")):UUID.fromString(header);
                } catch(RuntimeException invalid){response.sendError(400,"Invalid tenant identifier");return;}
                Boolean belongs=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM tenant_memberships m JOIN tenants t ON t.id=m.tenant_id WHERE m.user_id=? AND m.tenant_id=? AND t.active)",Boolean.class,userId,tenantId);
                if(!Boolean.TRUE.equals(belongs)){response.sendError(403,"User is not an active member of the requested tenant");return;}
                TenantContext.set(tenantId);
            } else if(request.getHeader("X-Artio-Tenant")!=null) {
                response.sendError(401,"Tenant selection requires authentication"); return;
            }
            chain.doFilter(request,response);
        } finally { TenantContext.clear(); }
    }
}
