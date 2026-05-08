package com.tcc.coordinator.api;

import com.tcc.coordinator.service.RecoveryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final RecoveryService recovery;

    public AdminController(RecoveryService recovery) {
        this.recovery = recovery;
    }

    /**
     * Manually trigger a recovery scan. Useful for tests and operational nudges.
     * Returns the number of stuck transactions that were re-driven this pass.
     */
    @PostMapping("/recovery/run")
    public ResponseEntity<Map<String, Integer>> runRecovery() {
        int n = recovery.recoverOnce();
        return ResponseEntity.ok(Map.of("driven", n));
    }
}
