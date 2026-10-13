package io.artio.api.cache;

import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

@Configuration
public class CacheConfiguration {
    @Bean CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        var valueSerializer = RedisSerializationContext.SerializationPair.fromSerializer(new JdkSerializationRedisSerializer());
        var defaults = RedisCacheConfiguration.defaultCacheConfig().serializeValuesWith(valueSerializer).disableCachingNullValues();
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults.entryTtl(Duration.ofMinutes(15)))
                .withCacheConfiguration("catalog-products", defaults.entryTtl(Duration.ofHours(2)))
                .withCacheConfiguration("price-lookups", defaults.entryTtl(Duration.ofHours(1)))
                .build();
    }
}
