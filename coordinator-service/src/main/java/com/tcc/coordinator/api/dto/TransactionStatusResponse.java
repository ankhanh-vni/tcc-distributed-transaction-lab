package com.tcc.coordinator.api.dto;

import com.tcc.coordinator.domain.GlobalTxState;
import com.tcc.coordinator.domain.ParticipantTxState;

import java.util.List;
import java.util.UUID;

public record TransactionStatusResponse(
        UUID txId,
        GlobalTxState state,
        int attemptCount,
        String lastError,
        List<Participant> participants
) {
    public record Participant(String name, ParticipantTxState state, String lastError) {}
}
