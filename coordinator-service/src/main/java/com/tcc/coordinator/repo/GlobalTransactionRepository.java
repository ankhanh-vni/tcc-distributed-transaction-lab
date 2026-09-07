package com.tcc.coordinator.repo;

import com.tcc.coordinator.domain.GlobalTransaction;
import com.tcc.coordinator.domain.GlobalTxState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GlobalTransactionRepository extends JpaRepository<GlobalTransaction, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from GlobalTransaction g where g.txId = :txId")
    Optional<GlobalTransaction> lockById(@Param("txId") UUID txId);

    @Query(value = """
            select * from global_transaction
            where state in ('STARTED', 'TRYING', 'TRY_FAILED', 'CONFIRMING', 'CANCELLING')
              and updated_at < clock_timestamp() - (:stuckMs * interval '1 millisecond')
              and (lease_until is null or lease_until <= clock_timestamp())
            order by updated_at, tx_id limit :batchSize
            """, nativeQuery = true)
    List<GlobalTransaction> findRecoverable(@Param("stuckMs") long stuckMs, @Param("batchSize") int batchSize);
}
