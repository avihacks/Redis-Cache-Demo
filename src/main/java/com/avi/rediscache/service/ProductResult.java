package com.avi.rediscache.service;

import com.avi.rediscache.model.Product;

/**
 * Wraps the product with metadata so the API response PROVES where the data came from.
 * source = "CACHE" (Redis hit) or "DATABASE" (miss, loaded from DB and cached).
 */
public record ProductResult(Product product, String source, long timeMs) {
}
