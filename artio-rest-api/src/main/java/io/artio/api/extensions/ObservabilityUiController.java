package io.artio.api.extensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import io.artio.extensions.ExtensionRuntimePort;
import io.artio.extensions.PluginManifest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin-authenticated, read-only same-origin entry point for the optional Grafana plugin UI. */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/admin/extensions/observability.artio")
public final class ObservabilityUiController {
    private static final String UI_PREFIX = "/api/v1/admin/extensions/observability.artio/ui";
    private final ObjectProvider<ExtensionRuntimePort> runtime;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ObservabilityUiController(ObjectProvider<ExtensionRuntimePort> runtime, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.runtime = runtime;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @GetMapping("/ui-session")
    public ResponseEntity<Void> openSession(JwtAuthenticationToken authentication, HttpServletRequest request) {
        requireAllowed();
        String forwarded = request.getHeader("X-Forwarded-Proto");
        boolean secure = request.isSecure() || forwarded != null && "https".equalsIgnoreCase(forwarded);
        ResponseCookie session = ResponseCookie.from("artio_observability_session", authentication.getToken().getTokenValue())
                .httpOnly(true).secure(secure).sameSite("Strict").path("/plugins/observability/")
                .maxAge(java.time.Duration.ofMinutes(10)).build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, session.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @GetMapping({"/ui", "/ui/**"})
    public ResponseEntity<byte[]> proxy(JwtAuthenticationToken authentication, HttpServletRequest request) {
        requireAllowed();
        String requestUri = request.getRequestURI();
        if (requestUri == null || !requestUri.startsWith(UI_PREFIX)) return ResponseEntity.notFound().build();
        String path = requestUri.substring(UI_PREFIX.length());
        if (path.isEmpty()) path = "/";
        ExtensionRuntimePort.UiProxyResponse response;
        ExtensionRuntimePort availableRuntime = runtime.getIfAvailable();
        if (availableRuntime == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        response = availableRuntime.proxyObservability("observability.artio", request.getMethod(), path,
                request.getQueryString(), authentication.getToken().getSubject(),
                authentication.getToken().getClaimAsString("email"));
        var builder = ResponseEntity.status(response.statusCode()).contentType(parseContentType(response.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff");
        response.headers().forEach((name, value) -> {
            if ("ETag".equalsIgnoreCase(name) || "Last-Modified".equalsIgnoreCase(name)) builder.header(name, value);
        });
        return builder.body(response.body());
    }

    private static org.springframework.http.MediaType parseContentType(String value) {
        try { return org.springframework.http.MediaType.parseMediaType(value); }
        catch (RuntimeException invalid) { return org.springframework.http.MediaType.APPLICATION_OCTET_STREAM; }
    }

    private void requireAllowed() {
        String manifest = jdbc.query("SELECT manifest::text FROM extension_plugins WHERE plugin_id='observability.artio' "
                        + "AND lifecycle_state<>'UNINSTALLED'", rs -> rs.next() ? rs.getString(1) : null);
        if (manifest == null) throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "Observability extension is unavailable");
        try {
            ExtensionSecurityPolicy.requireAllowed(jdbc, mapper.readValue(manifest, PluginManifest.class));
        } catch (java.io.IOException invalid) {
            throw new IllegalStateException("Observability extension manifest is invalid", invalid);
        }
    }
}
