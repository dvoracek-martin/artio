package io.artio.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class SharedRateLimitRedisIntegrationTests {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(
            "redis:7.4-alpine@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499"))
            .withExposedPorts(6379);

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void independentApiLimitersShareRedisQuotaAndFailClosedWhenRedisStops() throws Exception {
        var firstConnection = connectionFactory();
        var secondConnection = connectionFactory();
        try {
            StringRedisTemplate firstRedis = template(firstConnection);
            StringRedisTemplate secondRedis = template(secondConnection);
            var time = new AtomicLong(0);
            var firstApi = new RateLimitFilter(firstRedis, 2, true, mapper, time::get, true, true);
            var secondApi = new RateLimitFilter(secondRedis, 2, true, mapper, time::get, true, true);

            assertThat(request(firstApi, "192.0.2.1").getStatus()).isEqualTo(200);
            assertThat(request(secondApi, "192.0.2.1").getStatus()).isEqualTo(200);
            assertThat(request(firstApi, "192.0.2.1").getStatus()).isEqualTo(429);

            REDIS.stop();
            var unavailable = request(secondApi, "192.0.2.2");
            assertThat(unavailable.getStatus()).isEqualTo(503);
            assertThat(unavailable.getHeader("Retry-After")).isEqualTo("30");
            assertThat(mapper.readTree(unavailable.getContentAsByteArray()).path("code").asText())
                    .isEqualTo("SERVICE_UNAVAILABLE");
        } finally {
            firstConnection.destroy();
            secondConnection.destroy();
        }
    }

    private LettuceConnectionFactory connectionFactory() {
        var factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        return factory;
    }

    private StringRedisTemplate template(LettuceConnectionFactory factory) {
        var template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    private MockHttpServletResponse request(RateLimitFilter filter, String ip) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/products");
        request.setRemoteAddr(ip);
        request.setAttribute(ApiVersioningFilter.ORIGINAL_REQUEST_PATH_ATTRIBUTE, "/api/v1/products");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { });
        return response;
    }
}
