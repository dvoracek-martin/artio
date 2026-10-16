package io.artio.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiVersioningFilterTests {
    private final ApiVersioningFilter filter = new ApiVersioningFilter();

    @Test
    void publishesV1WithoutRewritingControllerPaths() throws Exception {
        var request = request("/api/v1/admin/extensions");
        var response = new MockHttpServletResponse();
        var forwarded = new AtomicReference<String>();
        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                forwarded.set(((jakarta.servlet.http.HttpServletRequest) servletRequest).getRequestURI()));

        assertThat(forwarded.get()).isEqualTo("/api/v1/admin/extensions");
        assertThat(response.getHeader("API-Version")).isEqualTo("1");
        assertThat(request.getAttribute(ApiVersioningFilter.ORIGINAL_REQUEST_PATH_ATTRIBUTE))
                .isEqualTo("/api/v1/admin/extensions");
    }

    @Test
    void doesNotPromoteAnUnversionedPathToV1() throws Exception {
        var request = request("/api/admin/extensions");
        var response = new MockHttpServletResponse();
        var forwarded = new AtomicReference<String>();
        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                forwarded.set(((jakarta.servlet.http.HttpServletRequest) servletRequest).getRequestURI()));

        assertThat(forwarded.get()).isEqualTo("/api/admin/extensions");
        assertThat(response.getHeader("API-Version")).isNull();
        assertThat(request.getAttribute(ApiVersioningFilter.ORIGINAL_REQUEST_PATH_ATTRIBUTE)).isNull();
    }

    private static MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest();
        request.setRequestURI(path);
        request.setServletPath(path);
        return request;
    }
}
