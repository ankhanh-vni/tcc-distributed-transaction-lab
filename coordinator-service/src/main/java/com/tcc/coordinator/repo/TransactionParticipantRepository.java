package com.tcc.coordinator.repo;

import com.tcc.coordinator.domain.TransactionParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TransactionParticipantRepository extends JpaRepository<TransactionParticipant, Long> {

    List<TransactionParticipant> findByTxIdOrderByIdAsc(UUID txId);
}
