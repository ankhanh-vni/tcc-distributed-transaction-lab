package com.tcc.common.dto;

import com.tcc.common.tcc.ParticipantState;

import java.util.UUID;

public record TccStatusResponse(UUID txId, ParticipantState state) {
}
