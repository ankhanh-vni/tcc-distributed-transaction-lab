package com.tcc.coordinator.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "tcc.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class RecoveryScheduler {
    private final RecoveryService recovery;
    public RecoveryScheduler(RecoveryService recovery) { this.recovery = recovery; }
    @Scheduled(fixedDelayString = "${tcc.recovery.fixed-delay-ms:5000}")
    public void runScheduled() { recovery.recoverOnce(); }
}
