package com.tcc.coordinator.service;

import com.tcc.coordinator.config.TccProperties;
import com.tcc.coordinator.domain.GlobalTxState;
import com.tcc.coordinator.repo.GlobalTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * MicroTx-style recovery: scan for global transactions stuck in a non-terminal state
 * for longer than a threshold and re-drive them by calling {@link CoordinatorService#drive}.
 * Because each individual phase is idempotent on the participant side and persisted on
 * the coordinator side, this loop is safe to run forever.
 */
@Component
@ConditionalOnProperty(name = "tcc.recovery.enabled", havingValue = "true", matchIfMissing = true)
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

    @Scheduled(fixedDelayString = "${tcc.recovery.fixed-delay-ms:5000}")
    public void runScheduled() {
        recoverOnce();
    }

    /**
     * One pass. Returns the number of transactions re-driven. Public so the admin
     * endpoint and tests can trigger it on demand.
     */
    public int recoverOnce() {
        var threshold = OffsetDateTime.now().minus(props.getRecovery().getStuckAfterMs(), ChronoUnit.MILLIS);
        List<UUID> stuck = findStuckIds(threshold);
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

    @Transactional(readOnly = true)
    protected List<UUID> findStuckIds(OffsetDateTime threshold) {
        return globals.findStuck(
                List.of(GlobalTxState.STARTED, GlobalTxState.TRYING, GlobalTxState.CONFIRMING, GlobalTxState.CANCELLING),
                threshold
        ).stream().map(g -> g.getTxId()).toList();
    }
}
