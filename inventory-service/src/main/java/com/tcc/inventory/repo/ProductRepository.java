package com.tcc.inventory.repo;

import com.tcc.inventory.domain.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findBySku(String sku);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.sku = :sku")
    Optional<Product> lockBySku(@Param("sku") String sku);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Product r where r.id = :id")
    Optional<Product> lockById(@Param("id") Long id);
}
