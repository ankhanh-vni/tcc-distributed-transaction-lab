package com.tcc.coordinator.service;

import com.tcc.coordinator.config.TccProperties;
import com.tcc.coordinator.repo.GlobalTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * MicroTx-style recovery: scan for global transactions stuck in a non-terminal state
 * for longer than a threshold and re-drive them by calling {@link CoordinatorService#drive}.
 * Because each individual phase is idempotent on the participant side and persisted on
 * the coordinator side, this loop is safe to run forever.
 */
@Component
public class RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    private final GlobalTransactionRepository globals;
    private final CoordinatorService coordinator;
    private final TccProperties props;

    public RecoveryService(GlobalTransactionRepository globals,
                           CoordinatorService coordinator,
                           TccProperties props) {
        this.globals = globals;
        this.coordinator = coordinator;
        this.props = props;
    }

    /**
     * One pass. Returns the number of eligible transactions submitted for driving. Public so the admin
     * endpoint and tests can trigger it on demand.
     */
    public int recoverOnce() {
        List<UUID> stuck = globals.findRecoverable(props.getRecovery().getStuckAfterMs(),
                props.getRecovery().getBatchSize()).stream().map(g -> g.getTxId()).toList();
        if (stuck.isEmpty()) return 0;
        log.info("[recovery] found {} stuck transaction(s)", stuck.size());
        for (UUID txId : stuck) {
            try {
                var newState = coordinator.drive(txId, null);
                log.info("[recovery] tx={} drove to {}", txId, newState);
            } catch (Exception ex) {
                log.warn("[recovery] tx={} drive failed: {}", txId, ex.getMessage());
            }
        }
        return stuck.size();
    }

}
