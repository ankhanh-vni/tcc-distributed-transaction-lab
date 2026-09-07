package com.tcc.coordinator.api;

import com.tcc.coordinator.api.dto.PlaceOrderRequest;
import com.tcc.coordinator.api.dto.TransactionStatusResponse;
import com.tcc.coordinator.domain.GlobalTransaction;
import com.tcc.coordinator.service.CoordinatorService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api")
public class OrderController {

    public static final String INJECT_FAIL_HEADER = "X-Inject-Fail-At-Participant";
    public static final String INJECT_CONFIRM_SLEEP_HEADER = "X-Inject-Confirm-Sleep-Millis";

    private final CoordinatorService coordinator;

    public OrderController(CoordinatorService coordinator) {
        this.coordinator = coordinator;
    }

    /**
     * Initiate a global TCC transaction for a place-order business operation.
     * The optional X-Inject-Fail-At-Participant header is forwarded to all participants
     * and accepts values like "TRY", "CONFIRM", or "CANCEL" — used by tests/curl
     * to drive specific failure scenarios.
     */
    @PostMapping("/orders")
    public ResponseEntity<TransactionStatusResponse> placeOrder(
            @Valid @RequestBody PlaceOrderRequest request,
            @RequestHeader(value = INJECT_FAIL_HEADER, required = false) String injectFailHeader,
            @RequestHeader(value = INJECT_CONFIRM_SLEEP_HEADER, required = false) String injectConfirmSleep,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        UUID txId = coordinator.placeOrder(request, injectFailHeader, injectConfirmSleep, idempotencyKey);
        return ResponseEntity.ok(buildStatus(txId));
    }

    @GetMapping("/transactions/{txId}")
    public ResponseEntity<TransactionStatusResponse> getStatus(@PathVariable UUID txId) {
        return ResponseEntity.ok(buildStatus(txId));
    }

    private TransactionStatusResponse buildStatus(UUID txId) {
        GlobalTransaction g = coordinator.findTransaction(txId);
        var participants = coordinator.findParticipants(txId).stream()
                .map(p -> new TransactionStatusResponse.Participant(p.getParticipant(), p.getState(), p.getLastError()))
                .toList();
        return new TransactionStatusResponse(g.getTxId(), g.getState(), g.getAttemptCount(), g.getLastError(), participants);
    }
}

