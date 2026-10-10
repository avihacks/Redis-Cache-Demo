package com.avi.rediscache.web;

import com.avi.rediscache.model.Product;
import com.avi.rediscache.service.ProductResult;
import com.avi.rediscache.service.ProductService;
import com.avi.rediscache.service.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ProductController {

    private final ProductService service;
    private final RateLimiter rateLimiter;

    public ProductController(ProductService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    public record ProductRequest(
            @NotBlank String name,
            @NotNull @DecimalMin("0.0") BigDecimal price,
            @NotNull @Min(0) Integer stock) {

        Product toEntity() {
            return new Product(name, price, stock);
        }
    }

    @GetMapping("/products")
    public List<Product> list() {
        return service.getAll();
    }

    @GetMapping("/products/{id}")
    public ProductResult get(@PathVariable long id, HttpServletRequest request) {
        if (!rateLimiter.allow(clientIp(request))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded, try again in a minute");
        }
        return service.getById(id);
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public Product create(@Valid @RequestBody ProductRequest body) {
        return service.create(body.toEntity());
    }

    @PutMapping("/products/{id}")
    public Product update(@PathVariable long id, @Valid @RequestBody ProductRequest body) {
        return service.update(id, body.toEntity());
    }

    @DeleteMapping("/products/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    @GetMapping("/stats")
    public ProductService.CacheStats stats() {
        return service.stats();
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    private String clientIp(HttpServletRequest request) {
        // Behind a proxy (Render, etc.) the real client IP is in X-Forwarded-For
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }
}
