package io.artio.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.web.util.ServletRequestPathUtils;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Routes the canonical /api/v1 contract to existing handlers while retaining legacy /api aliases. */
public final class ApiVersioningFilter extends OncePerRequestFilter {
    public static final String ORIGINAL_REQUEST_PATH_ATTRIBUTE = ApiVersioningFilter.class.getName() + ".originalRequestPath";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String contextPath = request.getContextPath();
        String requestPath = request.getRequestURI().substring(contextPath.length());
        String canonicalPath = unversionedPath(requestPath);
        if (canonicalPath == null) {
            chain.doFilter(request, response);
            return;
        }

        response.setHeader("API-Version", "1");
        request.setAttribute(ORIGINAL_REQUEST_PATH_ATTRIBUTE, request.getRequestURI());
        HttpServletRequest wrappedRequest = new HttpServletRequestWrapper(request) {
            @Override
            public String getRequestURI() {
                return contextPath + canonicalPath;
            }

            @Override
            public String getServletPath() {
                return canonicalPath;
            }
        };
        HttpServletResponse wrappedResponse = new HttpServletResponseWrapper(response) {
            @Override
            public void setHeader(String name, String value) {
                super.setHeader(name, rewriteLocation(name, value));
            }

            @Override
            public void addHeader(String name, String value) {
                super.addHeader(name, rewriteLocation(name, value));
            }

            @Override
            public void sendRedirect(String location) throws IOException {
                super.sendRedirect(rewriteLocation("Location", location));
            }
        };
        ServletRequestPathUtils.parseAndCache(wrappedRequest);
        chain.doFilter(wrappedRequest, wrappedResponse);
    }

    private static String unversionedPath(String path) {
        if ("/api/v1".equals(path)) return "/api";
        if (path.startsWith("/api/v1/")) return "/api" + path.substring("/api/v1".length());
        return null;
    }

    private static String rewriteLocation(String headerName, String value) {
        if (value != null && "Location".equalsIgnoreCase(headerName)) {
            if ("/api".equals(value)) return "/api/v1";
            if (value.startsWith("/api/")) return "/api/v1" + value.substring("/api".length());
        }
        return value;
    }
}
