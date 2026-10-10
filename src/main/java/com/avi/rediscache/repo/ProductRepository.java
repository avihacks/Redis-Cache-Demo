package com.avi.rediscache.repo;

import com.avi.rediscache.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
