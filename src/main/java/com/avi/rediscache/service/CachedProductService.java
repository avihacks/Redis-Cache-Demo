package com.avi.rediscache.service;

import com.avi.rediscache.model.Product;
import com.avi.rediscache.repo.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The SAME caching behaviour as ProductService, but done with Spring's cache ANNOTATIONS.
 * Spring wraps this bean in a proxy: the proxy checks Redis BEFORE calling the method body,
 * so the code inside a @Cacheable method only runs on a cache MISS.
 *
 * Keys in Redis look like   products::1   (cacheName::key)
 * (the hand-written version uses   product:1   so the two never collide).
 */
@Service
@CacheConfig(cacheNames = "products")   // default cache name for every annotation in this class
public class CachedProductService {

    private static final Logger log = LoggerFactory.getLogger(CachedProductService.class);

    private final ProductRepository repository;
    private final long dbLatencyMs;

    public CachedProductService(ProductRepository repository,
                                @Value("${app.simulated-db-latency-ms}") long dbLatencyMs) {
        this.repository = repository;
        this.dbLatencyMs = dbLatencyMs;
    }

    /**
     * @Cacheable: hit -> return cached value, method NOT executed.
     *             miss -> run the method, store the return value, return it.
     * key    : SpEL expression, #id = the method parameter named id
     * unless : evaluated on the RESULT; if true the result is NOT cached (don't cache out-of-stock items)
     */
    @Cacheable(key = "#id", unless = "#result.stock == 0")
    public Product getById(long id) {
        log.info(">>> CACHE MISS: loading product {} from the database", id);   // only prints on a miss
        simulateSlowDatabase();
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product " + id + " not found"));
    }

    /**
     * @CachePut: ALWAYS runs the method, then overwrites the cache entry with the return value.
     * Used on updates so the cache is refreshed instead of emptied.
     * IMPORTANT: it must return the same type that @Cacheable stores (Product), or the cache gets corrupted.
     */
    @CachePut(key = "#id")
    public Product update(long id, Product incoming) {
        Product existing = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product " + id + " not found"));
        existing.setName(incoming.getName());
        existing.setPrice(incoming.getPrice());
        existing.setStock(incoming.getStock());
        return repository.save(existing);
    }

    /** @CacheEvict: removes the entry for this key after the method succeeds. */
    @CacheEvict(key = "#id")
    public void delete(long id) {
        if (!repository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product " + id + " not found");
        }
        repository.deleteById(id);
    }

    /** allEntries = true wipes the whole "products" cache. */
    @CacheEvict(allEntries = true)
    public void clearCache() {
        // nothing to do: the annotation does the work
    }

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
}
