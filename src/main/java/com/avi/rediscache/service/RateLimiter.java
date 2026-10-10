package com.avi.rediscache.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Fixed-window rate limiter built on two Redis primitives:
 *   INCR   - atomic counter (safe even with many app instances)
 *   EXPIRE - the counter key disappears by itself when the window ends
 */
@Service
public class RateLimiter {

    private final StringRedisTemplate redis;
    private final int limitPerMinute;

    public RateLimiter(StringRedisTemplate redis, @Value("${app.rate-limit-per-minute}") int limitPerMinute) {
        this.redis = redis;
        this.limitPerMinute = limitPerMinute;
    }

    public boolean allow(String clientId) {
        long minute = System.currentTimeMillis() / 60_000;
        String key = "rate:" + clientId + ":" + minute;

        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, Duration.ofSeconds(65));   // first hit in this window sets the TTL
        }
        return count != null && count <= limitPerMinute;
    }
}
