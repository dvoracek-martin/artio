package io.artio.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private final StringRedisTemplate redis;
    private final int limit;
    private final boolean enabled;
    private final boolean redisEnabled;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final java.util.function.LongSupplier timeSource;
    private final java.util.concurrent.atomic.AtomicLong lastPrunedMinute = new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);
    private final ConcurrentHashMap<String, Window> localWindows = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public RateLimitFilter(StringRedisTemplate redis,
                           @Value("${artio.rate-limit.requests-per-minute:100}") int limit,
                           @Value("${artio.rate-limit.enabled:true}") boolean enabled,
                           @Value("${artio.cache.redis.enabled:false}") boolean redisEnabled,
                           com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this(redis, limit, enabled, mapper, System::currentTimeMillis, redisEnabled);
    }

    RateLimitFilter(StringRedisTemplate redis, int limit, boolean enabled,
            com.fasterxml.jackson.databind.ObjectMapper mapper, java.util.function.LongSupplier timeSource) {
        this(redis, limit, enabled, mapper, timeSource, true);
    }

    RateLimitFilter(StringRedisTemplate redis, int limit, boolean enabled,
            com.fasterxml.jackson.databind.ObjectMapper mapper, java.util.function.LongSupplier timeSource,
            boolean redisEnabled) {
        this.redis = redis;
        this.limit = limit;
        this.enabled = enabled;
        this.redisEnabled = redisEnabled;
        this.mapper = mapper;
        this.timeSource = timeSource;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled || request.getRequestURI().startsWith("/actuator/")) { chain.doFilter(request, response); return; }
        long minute = Math.floorDiv(timeSource.getAsLong(), 60_000L);
        long previous = lastPrunedMinute.get();
        if (previous < minute && lastPrunedMinute.compareAndSet(previous, minute))
            localWindows.entrySet().removeIf(entry -> entry.getValue().minute() < minute);
        String window = ":" + minute;
        boolean allowed = consume("artio:rate:ip:" + request.getRemoteAddr() + window, limit, minute);
        String apiKey = request.getHeader("X-API-Key");
        if (allowed && apiKey != null && !apiKey.isBlank()) {
            try {
                String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                allowed = consume("artio:rate:key:" + digest + window, 1000, minute);
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
        if (!allowed) {
            response.setHeader("Retry-After", "60");
            io.artio.api.error.ApiErrorWriter.write(mapper, request, response, org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "Request quota exceeded; retry after the current window");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean consume(String key, int threshold, long minute) {
        if (redisEnabled) {
            try {
                Long count = redis.opsForValue().increment(key);
                if (count != null && count == 1L) redis.expire(key, Duration.ofMinutes(2));
                return count != null && count <= threshold;
            } catch (RuntimeException unavailable) {
                // Use the local limiter immediately if the optional shared cache is unavailable.
            }
        }
        Window current = localWindows.compute(key, (storedKey, value) -> value == null || value.minute() != minute ? new Window(minute, 1) : new Window(minute, value.count() + 1));
        return current.count() <= threshold;
    }
    private record Window(long minute, long count) { }
}
