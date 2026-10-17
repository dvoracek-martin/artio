package io.artio.api.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Serializable;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class RedisCacheSharingIntegrationTests {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(
            "redis:7.4-alpine@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499"))
            .withExposedPorts(6379);

    @Test
    void independentApiCacheManagersShareCatalogEntriesThroughRedis() {
        LettuceConnectionFactory firstConnection = connectionFactory();
        LettuceConnectionFactory secondConnection = connectionFactory();
        CacheManager firstApiCache = new CacheConfiguration().redisCacheManager(firstConnection);
        CacheManager secondApiCache = new CacheConfiguration().redisCacheManager(secondConnection);
        try {
            var product = new CachedProduct("6dcc4eb2-7f51-4ce0-a293-4bdd7a7a57f3", "Coffee", "Kaffee");

            firstApiCache.getCache("catalog-products").put("published", product);

            assertThat(secondApiCache.getCache("catalog-products").get("published", CachedProduct.class))
                    .isEqualTo(product);
        } finally {
            firstApiCache.getCache("catalog-products").clear();
            firstConnection.destroy();
            secondConnection.destroy();
        }
    }

    private LettuceConnectionFactory connectionFactory() {
        var factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        return factory;
    }

    private record CachedProduct(String id, String name, String germanName) implements Serializable { }
}
