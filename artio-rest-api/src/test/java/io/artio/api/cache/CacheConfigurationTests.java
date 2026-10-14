package io.artio.api.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

class CacheConfigurationTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(CacheConfiguration.class);

    @Test
    void usesLocalCacheWhenRedisPluginIsNotEnabled() {
        context.run(application -> {
            assertThat(application).hasSingleBean(ConcurrentMapCacheManager.class);
            assertThat(application.getBean(ConcurrentMapCacheManager.class).getCacheNames())
                    .containsExactlyInAnyOrder("catalog-products", "price-lookups");
        });
    }

    @Test
    void usesRedisCacheWhenExplicitlyEnabled() {
        context.withPropertyValues("artio.cache.redis.enabled=true")
                .withBean(RedisConnectionFactory.class, () -> org.mockito.Mockito.mock(RedisConnectionFactory.class))
                .run(application -> assertThat(application).hasSingleBean(RedisCacheManager.class));
    }
}
