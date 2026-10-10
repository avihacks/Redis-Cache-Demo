package com.avi.rediscache;

import com.avi.rediscache.model.Product;
import com.avi.rediscache.repo.ProductRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/** Inserts a few demo rows on first start (only when the table is empty). */
@Component
public class DataSeeder implements CommandLineRunner {

    private final ProductRepository repository;

    public DataSeeder(ProductRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) {
        if (repository.count() > 0) {
            return;
        }
        repository.saveAll(List.of(
                new Product("Mechanical Keyboard", new BigDecimal("4999.00"), 25),
                new Product("27-inch Monitor", new BigDecimal("15999.00"), 10),
                new Product("USB-C Hub", new BigDecimal("1799.00"), 80),
                new Product("Wireless Mouse", new BigDecimal("999.00"), 120),
                new Product("Laptop Stand", new BigDecimal("1299.00"), 60)
        ));
    }
}
