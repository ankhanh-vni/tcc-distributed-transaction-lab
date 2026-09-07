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

    public boolean canTransitionTo(GlobalTxState target) {
        if (this == target) return true;
        return switch (this) {
            case STARTED -> target == TRYING || target == HEURISTIC;
            case TRYING -> target == CONFIRMING || target == CANCELLING || target == HEURISTIC;
            case TRY_FAILED -> target == CANCELLING || target == HEURISTIC;
            case CONFIRMING -> target == CONFIRMED || target == HEURISTIC;
            case CANCELLING -> target == CANCELLED || target == HEURISTIC;
            case CONFIRMED, CANCELLED, HEURISTIC -> false;
        };
    }

    public boolean isTerminal() {
        return this == CONFIRMED || this == CANCELLED || this == HEURISTIC;
    }

    public boolean isInFlight() {
        return this == TRYING || this == CONFIRMING || this == CANCELLING;
    }
}
