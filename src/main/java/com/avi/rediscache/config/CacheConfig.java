package com.avi.rediscache.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;

/**
 * Tells Spring's cache abstraction HOW to talk to Redis.
 *
 * Without this class, Spring Boot still works (it auto-creates a RedisCacheManager) but:
 *   - values are stored with Java serialization (unreadable binary, needs Serializable)
 *   - entries never expire unless you set spring.cache.redis.time-to-live
 * Here we store readable JSON and set an explicit TTL.
 */
@Configuration
public class CacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                          @Value("${app.cache-ttl-seconds}") long ttlSeconds) {

        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(ttlSeconds))          // every entry expires
                .disableCachingNullValues()                        // never store null results
                .serializeValuesWith(SerializationPair.fromSerializer(
                        new GenericJackson2JsonRedisSerializer())); // JSON instead of binary

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                // per-cache override example: the "products" cache gets its own TTL
                .withCacheConfiguration("products", defaults.entryTtl(Duration.ofSeconds(ttlSeconds)))
                .build();
    }
}
