package com.avi.rediscache.web;

import com.avi.rediscache.model.Product;
import com.avi.rediscache.service.CachedProductService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * v2 endpoints: identical API to /api/products but backed by the annotation-based service.
 * With annotations the method body is skipped on a hit, so we can't label the source inside
 * the service. Compare timeMs instead (fast = cache) and watch the ">>> CACHE MISS" log line.
 */
@RestController
@RequestMapping("/api/v2/products")
public class CachedProductController {

    public record TimedProduct(Product product, long timeMs) {
    }

    private final CachedProductService service;

    public CachedProductController(CachedProductService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public TimedProduct get(@PathVariable long id) {
        long start = System.nanoTime();
        Product product = service.getById(id);
        return new TimedProduct(product, (System.nanoTime() - start) / 1_000_000);
    }

    @PutMapping("/{id}")
    public Product update(@PathVariable long id, @Valid @RequestBody ProductController.ProductRequest body) {
        return service.update(id, body.toEntity());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    @DeleteMapping("/cache")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearCache() {
        service.clearCache();
    }
}
