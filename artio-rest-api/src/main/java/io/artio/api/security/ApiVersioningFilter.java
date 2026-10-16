package io.artio.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Publishes the API version for canonical routes; controllers expose no unversioned API aliases. */
public final class ApiVersioningFilter extends OncePerRequestFilter {
    public static final String ORIGINAL_REQUEST_PATH_ATTRIBUTE = ApiVersioningFilter.class.getName() + ".originalRequestPath";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String contextPath = request.getContextPath();
        String requestPath = request.getRequestURI().substring(contextPath.length());
        if ("/api/v1".equals(requestPath) || requestPath.startsWith("/api/v1/")) {
            response.setHeader("API-Version", "1");
            request.setAttribute(ORIGINAL_REQUEST_PATH_ATTRIBUTE, request.getRequestURI());
        }
        chain.doFilter(request, response);
    }
}
