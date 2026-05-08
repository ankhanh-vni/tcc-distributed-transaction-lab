package com.tcc.coordinator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcc.coordinator.api.dto.PlaceOrderRequest;
import com.tcc.coordinator.client.ParticipantCallException;
import com.tcc.coordinator.client.ParticipantClient;
import com.tcc.coordinator.domain.GlobalTransaction;
import com.tcc.coordinator.domain.GlobalTxState;
import com.tcc.coordinator.domain.ParticipantTxState;
import com.tcc.coordinator.domain.TransactionParticipant;
import com.tcc.coordinator.repo.GlobalTransactionRepository;
import com.tcc.coordinator.repo.TransactionParticipantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The heart of the TCC coordinator. Implements MicroTx-style log-and-resume:
 * every state transition is persisted on coordinator_db BEFORE any RPC, so that
 * on crash a recovery scan can re-drive the transaction to a terminal state.
 *
 * <p>The transactional operations live in {@link CoordinatorTxOps} because
 * Spring's proxy-based @Transactional does not apply to self-invocation.
 */
@Service
public class CoordinatorService {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorService.class);

    private final GlobalTransactionRepository globals;
    private final TransactionParticipantRepository participants;
    private final ParticipantClient client;
    private final CoordinatorTxOps tx;
    private final ObjectMapper json;

    public CoordinatorService(GlobalTransactionRepository globals,
                              TransactionParticipantRepository participants,
                              ParticipantClient client,
                              CoordinatorTxOps tx,
                              ObjectMapper json) {
        this.globals = globals;
        this.participants = participants;
        this.client = client;
        this.tx = tx;
        this.json = json;
    }

    /**
     * Entry point: initiate a global transaction for a place-order business request,
     * then synchronously drive it to a terminal state.
     */
    public UUID placeOrder(PlaceOrderRequest req, String injectFailHeader) {
        return placeOrder(req, injectFailHeader, null);
    }

    public UUID placeOrder(PlaceOrderRequest req, String injectFailHeader, String injectConfirmSleepMillis) {
        UUID txId = UUID.randomUUID();
        tx.seedTransaction(txId, req, (id, name) -> payloadFor(id, name, req));
        drive(txId, injectFailHeader, injectConfirmSleepMillis);
        return txId;
    }

    public GlobalTxState drive(UUID txId, String injectFailHeader) {
        return drive(txId, injectFailHeader, null);
    }

    /**
     * Drive the global transaction forward from whatever state it is in.
     * Safe to call repeatedly (recovery uses this same method, with no injections).
     */
    public GlobalTxState drive(UUID txId, String injectFailHeader, String injectConfirmSleepMillis) {
        GlobalTxState state = tx.transitionStartedToTrying(txId);
        if (state == GlobalTxState.TRYING) {
            state = runTryPhase(txId, injectFailHeader);
        }
        if (state == GlobalTxState.CONFIRMING) {
            state = runConfirmPhase(txId, injectConfirmSleepMillis);
        }
        if (state == GlobalTxState.CANCELLING) {
            state = runCancelPhase(txId);
        }
        return state;
    }

    /**
     * Phase 1 — Try. Walk participants in fixed order. On any failure, transition to CANCELLING.
     * On success of all, transition to CONFIRMING. Each per-participant outcome is persisted
     * before continuing.
     */
    private GlobalTxState runTryPhase(UUID txId, String injectFailHeader) {
        var ps = participants.findByTxIdOrderByIdAsc(txId);
        for (var p : ps) {
            if (p.getState() == ParticipantTxState.TRIED) {
                continue; // recovery resuming after crash
            }
            if (p.getState() != ParticipantTxState.PENDING) {
                continue;
            }
            try {
                client.callTry(p, injectFailHeader);
                tx.markParticipant(p.getId(), ParticipantTxState.TRIED, null);
            } catch (ParticipantCallException ex) {
                log.warn("[try] failed participant={} status={} cause={}", p.getParticipant(), ex.getHttpStatus(), ex.getMessage());
                tx.markParticipant(p.getId(), ParticipantTxState.FAILED, summarize(ex));
                return tx.transitionGlobal(txId, GlobalTxState.CANCELLING, summarize(ex));
            }
        }
        return tx.transitionGlobal(txId, GlobalTxState.CONFIRMING, null);
    }

    /**
     * Phase 2a — Confirm. Drive each TRIED participant to CONFIRMED. Failures DO NOT
     * cause cancellation: recovery will retry the same participants until they succeed
     * (the only way out of CONFIRMING is CONFIRMED, modulo HEURISTIC).
     */
    private GlobalTxState runConfirmPhase(UUID txId, String injectConfirmSleepMillis) {
        var ps = participants.findByTxIdOrderByIdAsc(txId);
        boolean allConfirmed = true;
        for (var p : ps) {
            if (p.getState() == ParticipantTxState.CONFIRMED) continue;
            if (p.getState() != ParticipantTxState.TRIED) {
                allConfirmed = false;
                continue;
            }
            try {
                client.callConfirm(p, injectConfirmSleepMillis);
                tx.markParticipant(p.getId(), ParticipantTxState.CONFIRMED, null);
            } catch (ParticipantCallException ex) {
                if (ex.isConflict()) {
                    tx.markParticipant(p.getId(), ParticipantTxState.FAILED, summarize(ex));
                    return tx.transitionGlobal(txId, GlobalTxState.HEURISTIC,
                            "Confirm rejected with 409 by " + p.getParticipant());
                }
                log.warn("[confirm] failed (will retry via recovery) participant={} cause={}",
                        p.getParticipant(), ex.getMessage());
                tx.markParticipantAttempted(p.getId(), summarize(ex));
                allConfirmed = false;
            }
        }
        if (allConfirmed) {
            return tx.transitionGlobal(txId, GlobalTxState.CONFIRMED, null);
        }
        return GlobalTxState.CONFIRMING;
    }

    /**
     * Phase 2b — Cancel. Drive every participant to CANCELLED, including PENDING ones
     * (preventive cancel — closes the race window where a slow Try arrives after we decide to cancel).
     */
    private GlobalTxState runCancelPhase(UUID txId) {
        var ps = participants.findByTxIdOrderByIdAsc(txId);
        boolean allCancelled = true;
        for (var p : ps) {
            if (p.getState() == ParticipantTxState.CANCELLED) continue;
            try {
                client.callCancel(txId, p);
                tx.markParticipant(p.getId(), ParticipantTxState.CANCELLED, null);
            } catch (ParticipantCallException ex) {
                if (ex.isConflict()) {
                    tx.markParticipant(p.getId(), ParticipantTxState.FAILED, summarize(ex));
                    return tx.transitionGlobal(txId, GlobalTxState.HEURISTIC,
                            "Cancel rejected with 409 by " + p.getParticipant());
                }
                log.warn("[cancel] failed (will retry via recovery) participant={} cause={}",
                        p.getParticipant(), ex.getMessage());
                tx.markParticipantAttempted(p.getId(), summarize(ex));
                allCancelled = false;
            }
        }
        if (allCancelled) {
            return tx.transitionGlobal(txId, GlobalTxState.CANCELLED, null);
        }
        return GlobalTxState.CANCELLING;
    }

    private String payloadFor(UUID txId, String participant, PlaceOrderRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("txId", txId.toString());
        switch (participant) {
            case "inventory" -> {
                body.put("sku", req.sku());
                body.put("qty", req.qty());
            }
            case "payment" -> {
                body.put("customerId", req.customerId());
                body.put("amount", req.amount().setScale(2, java.math.RoundingMode.HALF_UP));
            }
            case "order" -> {
                body.put("customerId", req.customerId());
                body.put("sku", req.sku());
                body.put("qty", req.qty());
                body.put("amount", req.amount().setScale(2, java.math.RoundingMode.HALF_UP));
            }
            default -> throw new IllegalArgumentException("unknown participant: " + participant);
        }
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("could not serialize payload for " + participant, ex);
        }
    }

    private static String summarize(ParticipantCallException ex) {
        return "[" + ex.getParticipant() + ":" + ex.getPhase() + ":" + ex.getHttpStatus() + "] " + ex.getMessage();
    }

    @Transactional(readOnly = true)
    public GlobalTransaction findTransaction(UUID txId) {
        return globals.findById(txId).orElseThrow(() -> new IllegalArgumentException("unknown tx=" + txId));
    }

    @Transactional(readOnly = true)
    public List<TransactionParticipant> findParticipants(UUID txId) {
        return participants.findByTxIdOrderByIdAsc(txId);
    }
}
