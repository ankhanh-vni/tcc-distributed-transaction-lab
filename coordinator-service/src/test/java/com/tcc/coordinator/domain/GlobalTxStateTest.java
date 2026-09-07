package com.tcc.coordinator.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GlobalTxStateTest {
    @Test
    void terminalStatesAreAbsorbing() {
        for (var state : GlobalTxState.values()) {
            if (!state.isTerminal()) continue;
            for (var target : GlobalTxState.values()) {
                assertThat(state.canTransitionTo(target)).as("%s -> %s", state, target).isEqualTo(state == target);
            }
        }
    }
    @Test
    void confirmDecisionIsIrreversible() {
        assertThat(GlobalTxState.CONFIRMING.canTransitionTo(GlobalTxState.CANCELLING)).isFalse();
        assertThat(GlobalTxState.CONFIRMING.canTransitionTo(GlobalTxState.TRYING)).isFalse();
        assertThat(GlobalTxState.CONFIRMING.canTransitionTo(GlobalTxState.CONFIRMED)).isTrue();
        assertThat(GlobalTxState.CONFIRMING.canTransitionTo(GlobalTxState.HEURISTIC)).isTrue();
        assertThat(GlobalTxState.CANCELLING.canTransitionTo(GlobalTxState.CONFIRMING)).isFalse();
    }
}
