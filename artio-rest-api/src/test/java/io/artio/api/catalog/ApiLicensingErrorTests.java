package io.artio.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.artio.api.licensing.EnterpriseFeature;
import io.artio.api.licensing.FeatureNotLicensedException;
import io.artio.api.security.ApiVersioningFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiLicensingErrorTests {
    @Test
    void unavailableFeatureReturnsStable403CodeAndOriginalVersionedPath() {
        var request = new MockHttpServletRequest("GET", "/api/admin/reports/advanced");
        request.setAttribute(ApiVersioningFilter.ORIGINAL_REQUEST_PATH_ATTRIBUTE, "/api/v1/admin/reports/advanced");
        var result = new ApiExceptionHandler().notLicensed(new FeatureNotLicensedException(EnterpriseFeature.ADVANCED_REPORTING), request);
        assertThat(result.getStatusCode().value()).isEqualTo(403);
        assertThat(result.getBody().code()).isEqualTo("FEATURE_NOT_LICENSED");
        assertThat(result.getBody().path()).isEqualTo("/api/v1/admin/reports/advanced");
    }
}
