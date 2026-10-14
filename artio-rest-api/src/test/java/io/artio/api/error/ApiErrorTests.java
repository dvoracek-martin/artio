package io.artio.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiErrorTests {
    @Test
    void mapsHttpStatusesToStableClientCodes() {
        assertThat(ApiError.codeFor(400)).isEqualTo("BAD_REQUEST");
        assertThat(ApiError.codeFor(401)).isEqualTo("UNAUTHORIZED");
        assertThat(ApiError.codeFor(403)).isEqualTo("FORBIDDEN");
        assertThat(ApiError.codeFor(404)).isEqualTo("NOT_FOUND");
        assertThat(ApiError.codeFor(409)).isEqualTo("CONFLICT");
        assertThat(ApiError.codeFor(429)).isEqualTo("RATE_LIMITED");
        assertThat(ApiError.codeFor(503)).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(ApiError.codeFor(500)).isEqualTo("SERVER_ERROR");
    }
}
