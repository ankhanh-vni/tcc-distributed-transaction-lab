package com.tcc.payment.repo;

import com.tcc.payment.domain.PaymentAuthorization;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PaymentAuthorizationRepository extends JpaRepository<PaymentAuthorization, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PaymentAuthorization a where a.txId = :txId")
    Optional<PaymentAuthorization> lockById(@Param("txId") UUID txId);
}
