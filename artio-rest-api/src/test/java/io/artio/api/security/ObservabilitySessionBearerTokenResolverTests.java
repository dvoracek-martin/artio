package io.artio.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ObservabilitySessionBearerTokenResolverTests {
    private final SecurityConfiguration configuration = new SecurityConfiguration();

    @Test
    void acceptsTheShortLivedCookieOnlyOnTheObservabilityUiProxyPath() {
        var resolver = configuration.observabilitySessionBearerTokenResolver();
        var ui = new MockHttpServletRequest();
        ui.setRequestURI("/api/v1/admin/extensions/observability.artio/ui/public/build/app.js");
        ui.setCookies(new Cookie("artio_observability_session", "admin-jwt"));
        assertThat(resolver.resolve(ui)).isEqualTo("admin-jwt");

        var adminApi = new MockHttpServletRequest();
        adminApi.setRequestURI("/api/v1/admin/extensions");
        adminApi.setCookies(new Cookie("artio_observability_session", "admin-jwt"));
        assertThat(resolver.resolve(adminApi)).isNull();
    }

    @Test
    void bearerHeaderTakesPrecedenceOnObservabilityRequests() {
        var request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/admin/extensions/observability.artio/ui/");
        request.addHeader("Authorization", "Bearer header-token");
        request.setCookies(new Cookie("artio_observability_session", "cookie-token"));
        assertThat(configuration.observabilitySessionBearerTokenResolver().resolve(request)).isEqualTo("header-token");
    }
}
