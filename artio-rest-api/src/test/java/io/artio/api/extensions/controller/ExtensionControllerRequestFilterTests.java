package io.artio.api.extensions.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ExtensionControllerRequestFilterTests {
    private static final String TOKEN = "filter-test-controller-credential-at-least-32-bytes";
    private final ExtensionControllerRequestFilter filter = new ExtensionControllerRequestFilter(
            new ExtensionControllerSecurity(TOKEN), new ObjectMapper());

    @Test
    void rejectsUnauthenticatedRequestBeforeReadingAnyBody() throws Exception {
        var request = new MockHttpServletRequest("POST", "/internal/v1/extensions/install") {
            @Override public jakarta.servlet.ServletInputStream getInputStream() {
                throw new AssertionError("Unauthorized request body must not be read");
            }
        };
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { throw new AssertionError("Must reject"); });
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("INVALID_CONTROLLER_CREDENTIAL").doesNotContain(TOKEN);
    }

    @Test
    void rejectsFixedLengthOversizeEnvelope() throws Exception {
        verifyOversize(false);
    }

    @Test
    void rejectsChunkedOversizeEnvelope() throws Exception {
        verifyOversize(true);
    }

    private void verifyOversize(boolean unknownLength) throws Exception {
        var request = new MockHttpServletRequest("POST", "/internal/v1/extensions/install") {
            @Override public long getContentLengthLong() { return unknownLength ? -1 : super.getContentLengthLong(); }
            @Override public int getContentLength() { return unknownLength ? -1 : super.getContentLength(); }
        };
        request.addHeader("Authorization", "Bearer " + TOKEN);
        request.setContent(new byte[8 * 1024 * 1024 + 1]);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { throw new AssertionError("Must reject"); });
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("CONTROLLER_PAYLOAD_TOO_LARGE");
    }

    @Test
    void forwardsAuthorizedBytesWithoutChangingContent() throws Exception {
        var request = new MockHttpServletRequest("POST", "/internal/v1/telemetry/observability.artio/metrics");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        byte[] body = new byte[] {0, 1, 2, 127, -1};
        request.setContent(body);
        var forwarded = new AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (wrapped, response) -> {
            assertThat(wrapped.getInputStream().readAllBytes()).containsExactly(body);
            forwarded.set(true);
        });
        assertThat(forwarded).isTrue();
    }

    @Test
    void leavesNonControllerHealthRouteAvailable() throws Exception {
        var forwarded = new AtomicBoolean();
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), new MockHttpServletResponse(),
                (request, response) -> forwarded.set(true));
        assertThat(forwarded).isTrue();
    }

    @Test
    void acceptsEnvelopeExactlyAtTransportLimit() throws Exception {
        var request = new MockHttpServletRequest("POST", "/internal/v1/extensions/enable");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        request.setContent(new byte[8 * 1024 * 1024]);
        var forwarded = new AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (wrapped, response) -> {
            assertThat(wrapped.getInputStream().readAllBytes()).hasSize(8 * 1024 * 1024);
            forwarded.set(true);
        });
        assertThat(forwarded).isTrue();
    }
}
