package com.tcc.payment.repo;

import com.tcc.payment.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByCustomerId(String customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.customerId = :customerId")
    Optional<Account> lockByCustomerId(@Param("customerId") String customerId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Account r where r.id = :id")
    Optional<Account> lockById(@Param("id") Long id);
}
