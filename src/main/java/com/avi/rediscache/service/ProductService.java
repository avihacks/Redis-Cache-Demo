package com.avi.rediscache.service;

import com.avi.rediscache.model.Product;
import com.avi.rediscache.repo.ProductRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * CACHE-ASIDE (lazy loading) pattern:
 *
 *   READ : check Redis -> hit? return.  miss? read DB -> write to Redis with TTL -> return.
 *   WRITE: update DB first -> then DELETE the cache key (next read repopulates it).
 */
@Service
public class ProductService {

    private static final String KEY_PREFIX = "product:";
    private static final String NULL_MARKER = "__NULL__";   // guards against cache penetration
    private static final String HITS = "stats:hits";
    private static final String MISSES = "stats:misses";

    private final ProductRepository repository;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final long ttlSeconds;
    private final long dbLatencyMs;

    public ProductService(ProductRepository repository,
                          StringRedisTemplate redis,
                          ObjectMapper mapper,
                          @Value("${app.cache-ttl-seconds}") long ttlSeconds,
                          @Value("${app.simulated-db-latency-ms}") long dbLatencyMs) {
        this.repository = repository;
        this.redis = redis;
        this.mapper = mapper;
        this.ttlSeconds = ttlSeconds;
        this.dbLatencyMs = dbLatencyMs;
    }

    // ---------------------------------------------------------------- READ

    public ProductResult getById(long id) {
        long start = System.nanoTime();
        String key = KEY_PREFIX + id;

        // 1) Ask Redis first
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            redis.opsForValue().increment(HITS);
            if (NULL_MARKER.equals(cached)) {
                throw notFound(id);                 // negative cache hit: DB not touched
            }
            return new ProductResult(fromJson(cached), "CACHE", elapsedMs(start));
        }

        // 2) Cache MISS -> go to the database (slow path)
        redis.opsForValue().increment(MISSES);
        simulateSlowDatabase();
        Product product = repository.findById(id).orElse(null);

        if (product == null) {
            // Cache the "not found" for a short time so repeated bad ids don't hammer the DB
            redis.opsForValue().set(key, NULL_MARKER, Duration.ofSeconds(30));
            throw notFound(id);
        }

        // 3) Populate the cache. TTL + random jitter avoids many keys expiring at the same instant
        long jitter = ThreadLocalRandom.current().nextLong(0, 30);
        redis.opsForValue().set(key, toJson(product), Duration.ofSeconds(ttlSeconds + jitter));
        return new ProductResult(product, "DATABASE", elapsedMs(start));
    }

    public List<Product> getAll() {
        return repository.findAll();   // intentionally not cached: lists change often & are large
    }

    // ---------------------------------------------------------------- WRITE

    public Product create(Product product) {
        return repository.save(product);
    }

    public Product update(long id, Product incoming) {
        Product existing = repository.findById(id).orElseThrow(() -> notFound(id));
        existing.setName(incoming.getName());
        existing.setPrice(incoming.getPrice());
        existing.setStock(incoming.getStock());
        Product saved = repository.save(existing);
        redis.delete(KEY_PREFIX + id);      // invalidate, don't update (avoids stale/racy writes)
        return saved;
    }

    public void delete(long id) {
        if (!repository.existsById(id)) {
            throw notFound(id);
        }
        repository.deleteById(id);
        redis.delete(KEY_PREFIX + id);
    }

    // ---------------------------------------------------------------- STATS

    public CacheStats stats() {
        long hits = parse(redis.opsForValue().get(HITS));
        long misses = parse(redis.opsForValue().get(MISSES));
        long total = hits + misses;
        double ratio = total == 0 ? 0 : Math.round((hits * 10000.0) / total) / 100.0;
        return new CacheStats(hits, misses, ratio);
    }

    public record CacheStats(long hits, long misses, double hitRatioPercent) {
    }

    // ---------------------------------------------------------------- helpers

    private void simulateSlowDatabase() {
        if (dbLatencyMs <= 0) {
            return;
        }
        try {
            Thread.sleep(dbLatencyMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private long parse(String value) {
        return value == null ? 0 : Long.parseLong(value);
    }

    private ResponseStatusException notFound(long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Product " + id + " not found");
    }

    private String toJson(Product product) {
        try {
            return mapper.writeValueAsString(product);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize product", e);
        }
    }

    private Product fromJson(String json) {
        try {
            return mapper.readValue(json, Product.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not deserialize product", e);
        }
    }
}
