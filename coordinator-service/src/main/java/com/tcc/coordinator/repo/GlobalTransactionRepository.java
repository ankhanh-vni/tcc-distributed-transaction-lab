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

    @Query("select g from GlobalTransaction g where g.state in :states and g.updatedAt < :before order by g.updatedAt asc")
    List<GlobalTransaction> findStuck(@Param("states") List<GlobalTxState> states,
                                       @Param("before") OffsetDateTime before);
}
