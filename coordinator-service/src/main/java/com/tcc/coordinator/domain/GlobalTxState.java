package com.tcc.coordinator.domain;

public enum GlobalTxState {
    STARTED,
    TRYING,
    TRY_FAILED,
    CONFIRMING,
    CONFIRMED,
    CANCELLING,
    CANCELLED,
    HEURISTIC;

    public boolean isTerminal() {
        return this == CONFIRMED || this == CANCELLED || this == HEURISTIC;
    }

    public boolean isInFlight() {
        return this == TRYING || this == CONFIRMING || this == CANCELLING;
    }
}
