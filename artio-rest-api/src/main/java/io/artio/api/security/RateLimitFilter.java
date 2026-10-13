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
    private final ConcurrentHashMap<String, Window> localWindows = new ConcurrentHashMap<>();

    public RateLimitFilter(StringRedisTemplate redis,
                           @Value("${artio.rate-limit.requests-per-minute:100}") int limit,
                           @Value("${artio.rate-limit.enabled:true}") boolean enabled) {
        this.redis = redis;
        this.limit = limit;
        this.enabled = enabled;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled || request.getRequestURI().startsWith("/actuator/")) { chain.doFilter(request, response); return; }
        String window = ":" + Math.floorDiv(System.currentTimeMillis(), 60_000L);
        boolean allowed = consume("artio:rate:ip:" + request.getRemoteAddr() + window, limit);
        String apiKey = request.getHeader("X-API-Key");
        if (allowed && apiKey != null && !apiKey.isBlank()) {
            try {
                String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                allowed = consume("artio:rate:key:" + digest + window, 1000);
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
        if (!allowed) { response.setStatus(429); response.setHeader("Retry-After", "60"); response.setContentType("application/json"); response.getWriter().write("{\"error\":\"rate_limit_exceeded\"}"); return; }
        chain.doFilter(request, response);
    }

    private boolean consume(String key, int threshold) {
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) redis.expire(key, Duration.ofMinutes(2));
            return count != null && count <= threshold;
        } catch (RuntimeException unavailable) {
            long minute = Math.floorDiv(System.currentTimeMillis(), 60_000L);
            Window current = localWindows.compute(key, (storedKey, value) -> value == null || value.minute() != minute ? new Window(minute, 1) : new Window(minute, value.count() + 1));
            return current.count() <= threshold;
        }
    }
    private record Window(long minute, long count) { }
}
