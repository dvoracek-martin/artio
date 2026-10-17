package io.artio.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTests {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicLong time = new AtomicLong(0);

    @Test
    void unavailableRedisStillEnforcesIpQuotaResetsNextMinuteAndUsesVersionedErrorContract() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("Fixture Redis unavailable"));
        var filter = new RateLimitFilter(redis, 2, true, mapper, time::get);
        assertThat(request(filter, "192.0.2.1", null).getStatus()).isEqualTo(200);
        assertThat(request(filter, "192.0.2.1", null).getStatus()).isEqualTo(200);
        var denied = request(filter, "192.0.2.1", null);
        assertThat(denied.getStatus()).isEqualTo(429);
        assertThat(denied.getHeader("Retry-After")).isEqualTo("60");
        var error = mapper.readTree(denied.getContentAsByteArray());
        assertThat(error.path("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(error.path("path").asText()).isEqualTo("/api/v1/products");
        assertThat(request(filter, "192.0.2.2", null).getStatus()).isEqualTo(200);
        time.set(60_000);
        assertThat(request(filter, "192.0.2.1", null).getStatus()).isEqualTo(200);
    }

    @Test
    void redisEnforcesTheSharedApiKeyBudgetAcrossDifferentIpsWithoutUsingRawCredentialsAsKeys() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var counters = new java.util.concurrent.ConcurrentHashMap<String, AtomicLong>();
        when(values.increment(anyString())).thenAnswer(call -> counters.computeIfAbsent(call.getArgument(0), ignored -> new AtomicLong()).incrementAndGet());
        var filter = new RateLimitFilter(redis, 2, true, mapper, time::get);
        for (int index = 1; index <= 1000; index++)
            assertThat(request(filter, "2001:db8::" + Integer.toHexString(index), "fixture-only-api-key").getStatus()).isEqualTo(200);
        assertThat(request(filter, "2001:db8::ffff", "fixture-only-api-key").getStatus()).isEqualTo(429);
        assertThat(counters.keySet()).noneMatch(key -> key.contains("fixture-only-api-key"));
    }

    @Test
    void separateApiLimitersShareTheRedisIpBudget() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var counters = new java.util.concurrent.ConcurrentHashMap<String, AtomicLong>();
        when(values.increment(anyString())).thenAnswer(call -> counters.computeIfAbsent(call.getArgument(0), ignored -> new AtomicLong()).incrementAndGet());
        var firstApi = new RateLimitFilter(redis, 2, true, mapper, time::get, true, true);
        var secondApi = new RateLimitFilter(redis, 2, true, mapper, time::get, true, true);

        assertThat(request(firstApi, "192.0.2.1", null).getStatus()).isEqualTo(200);
        assertThat(request(secondApi, "192.0.2.1", null).getStatus()).isEqualTo(200);
        assertThat(request(firstApi, "192.0.2.1", null).getStatus()).isEqualTo(429);
    }

    @Test
    void explicitDisabledPolicyAvoidsRedisAndThrottling() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        var filter = new RateLimitFilter(redis, 1, false, mapper, time::get);
        for (int index = 0; index < 3; index++) assertThat(request(filter, "192.0.2.1", null).getStatus()).isEqualTo(200);
        verifyNoInteractions(redis);
    }

    @Test
    void requiredSharedLimiterFailsClosedWhenRedisIsUnavailable() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("Fixture Redis unavailable"));
        var filter = new RateLimitFilter(redis, 2, true, mapper, time::get, true, true);

        var unavailable = request(filter, "192.0.2.1", null);
        assertThat(unavailable.getStatus()).isEqualTo(503);
        assertThat(unavailable.getHeader("Retry-After")).isEqualTo("30");
        var error = mapper.readTree(unavailable.getContentAsByteArray());
        assertThat(error.path("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(error.path("path").asText()).isEqualTo("/api/v1/products");
        assertThat(request(filter, "192.0.2.1", null).getStatus()).isEqualTo(503);
    }

    @Test
    void sharedLimiterCannotBeRequiredWhenRedisStorageIsDisabled() {
        var redis = mock(StringRedisTemplate.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new RateLimitFilter(redis, 2, true, mapper, time::get, false, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires Redis-backed");
        verifyNoInteractions(redis);
    }

    private MockHttpServletResponse request(RateLimitFilter filter, String ip, String apiKey) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/products");
        request.setRemoteAddr(ip);
        request.setAttribute(ApiVersioningFilter.ORIGINAL_REQUEST_PATH_ATTRIBUTE, "/api/v1/products");
        if (apiKey != null) request.addHeader("X-API-Key", apiKey);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { });
        return response;
    }
}
