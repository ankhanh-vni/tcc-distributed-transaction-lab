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
        return placeOrder(req, injectFailHeader, injectConfirmSleepMillis, null);
    }

    public UUID placeOrder(PlaceOrderRequest req, String injectFailHeader, String injectConfirmSleepMillis,
                           String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must contain 1..128 letters, digits, '.', '_', ':' or '-'");
        }
        var seed = tx.seedOrGet(idempotencyKey, idempotencyKey == null ? null : fingerprint(req), req,
                (id, name) -> payloadFor(id, name, req));
        // Replays may resume an abandoned transaction, but never reapply fault-injection headers.
        drive(seed.txId(), seed.created() ? injectFailHeader : null,
                seed.created() ? injectConfirmSleepMillis : null);
        return seed.txId();
    }

    private String fingerprint(PlaceOrderRequest req) {
        try {
            // Fixed field order and exact money normalization make JSON field order
            // and values such as 100, 100.0 and 100.00 equivalent without ambiguous concatenation.
            return json.writeValueAsString(List.of(req.customerId(), req.sku(), req.qty(),
                    req.amount().setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString()));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not fingerprint order", ex);
        }
    }

    public GlobalTxState drive(UUID txId, String injectFailHeader) {
        return drive(txId, injectFailHeader, null);
    }

    /**
     * Drive the global transaction forward from whatever state it is in.
     * Safe to call repeatedly (recovery uses this same method, with no injections).
     */
    public GlobalTxState drive(UUID txId, String injectFailHeader, String injectConfirmSleepMillis) {
        UUID token = UUID.randomUUID();
        if (!tx.acquire(txId, token)) return findTransaction(txId).getState();
        try {
            GlobalTxState state = findTransaction(txId).getState();
            if (state == GlobalTxState.STARTED) state = tx.transitionGlobal(txId, token, GlobalTxState.TRYING, null);
            if (state == GlobalTxState.TRY_FAILED) state = tx.transitionGlobal(txId, token, GlobalTxState.CANCELLING, "Recovered failed Try");
            if (state == GlobalTxState.TRYING) state = runTryPhase(txId, token, injectFailHeader);
            if (state == GlobalTxState.CONFIRMING) state = runConfirmPhase(txId, token, injectConfirmSleepMillis);
            if (state == GlobalTxState.CANCELLING) state = runCancelPhase(txId, token);
            return state;
        } catch (StaleDriverException ex) {
            log.info("[drive] lease lost tx={}; discarding stale result", txId);
            return findTransaction(txId).getState();
        } finally {
            tx.release(txId, token);
        }
    }

    /**
     * Phase 1 — Try. Walk participants in fixed order. On any failure, transition to CANCELLING.
     * On success of all, transition to CONFIRMING. Each per-participant outcome is persisted
     * before continuing.
     */
    private GlobalTxState runTryPhase(UUID txId, UUID token, String injectFailHeader) {
        var ps = participants.findByTxIdOrderByIdAsc(txId);
        if (!completeRegistration(ps)) return tx.transitionGlobal(txId, token, GlobalTxState.HEURISTIC, "Incomplete participant log");
        if (ps.stream().anyMatch(p -> p.getState() == ParticipantTxState.CONFIRMED)) {
            return tx.transitionGlobal(txId, token, GlobalTxState.HEURISTIC, "Confirmed participant before Confirm decision");
        }
        if (ps.stream().anyMatch(p -> p.getState() == ParticipantTxState.FAILED || p.getState() == ParticipantTxState.CANCELLED)) {
            return tx.transitionGlobal(txId, token, GlobalTxState.CANCELLING, "Recovered unsuccessful Try");
        }
        for (var p : ps) {
            if (p.getState() == ParticipantTxState.TRIED) {
                continue; // recovery resuming after crash
            }
            if (p.getState() != ParticipantTxState.PENDING) {
                continue;
            }
            try {
                tx.beforeCall(txId, token, p.getId(), GlobalTxState.TRYING);
                client.callTry(p, injectFailHeader);
                tx.markParticipant(txId, token, p.getId(), GlobalTxState.TRYING, ParticipantTxState.TRIED, null);
            } catch (ParticipantCallException ex) {
                log.warn("[try] failed participant={} status={} cause={}", p.getParticipant(), ex.getHttpStatus(), ex.getMessage());
                return tx.failParticipant(txId, token, p.getId(), GlobalTxState.TRYING, summarize(ex));
            }
        }
        return tx.transitionGlobal(txId, token, GlobalTxState.CONFIRMING, null);
    }

    /**
     * Phase 2a — Confirm. Drive each TRIED participant to CONFIRMED. Failures DO NOT
     * cause cancellation: recovery will retry the same participants until they succeed
     * (the only way out of CONFIRMING is CONFIRMED, modulo HEURISTIC).
     */
    private GlobalTxState runConfirmPhase(UUID txId, UUID token, String injectConfirmSleepMillis) {
        var ps = participants.findByTxIdOrderByIdAsc(txId);
        if (!completeRegistration(ps) || ps.stream().anyMatch(p -> p.getState() != ParticipantTxState.TRIED && p.getState() != ParticipantTxState.CONFIRMED)) {
            return tx.transitionGlobal(txId, token, GlobalTxState.HEURISTIC, "Inconsistent Confirm log");
        }
        boolean allConfirmed = true;
        for (var p : ps) {
            if (p.getState() == ParticipantTxState.CONFIRMED) continue;
            if (p.getState() != ParticipantTxState.TRIED) {
                allConfirmed = false;
                continue;
            }
            try {
                tx.beforeCall(txId, token, p.getId(), GlobalTxState.CONFIRMING);
                client.callConfirm(p, injectConfirmSleepMillis);
                tx.markParticipant(txId, token, p.getId(), GlobalTxState.CONFIRMING, ParticipantTxState.CONFIRMED, null);
            } catch (ParticipantCallException ex) {
                if (ex.isConflict()) {
                    return tx.failParticipant(txId, token, p.getId(), GlobalTxState.CONFIRMING,
                            "Confirm rejected with 409 by " + p.getParticipant() + ": " + summarize(ex));
                }
                log.warn("[confirm] failed (will retry via recovery) participant={} cause={}",
                        p.getParticipant(), ex.getMessage());
                tx.markParticipantAttempted(txId, token, p.getId(), GlobalTxState.CONFIRMING, summarize(ex));
                allConfirmed = false;
            }
        }
        if (allConfirmed) {
            return tx.transitionGlobal(txId, token, GlobalTxState.CONFIRMED, null);
        }
        return GlobalTxState.CONFIRMING;
    }

    /**
     * Phase 2b — Cancel. Drive every participant to CANCELLED, including PENDING ones
     * (preventive cancel — closes the race window where a slow Try arrives after we decide to cancel).
     */
    private GlobalTxState runCancelPhase(UUID txId, UUID token) {
        var ps = participants.findByTxIdOrderByIdAsc(txId);
        if (!completeRegistration(ps) || ps.stream().anyMatch(p -> p.getState() == ParticipantTxState.CONFIRMED)) {
            return tx.transitionGlobal(txId, token, GlobalTxState.HEURISTIC, "Inconsistent Cancel log");
        }
        boolean allCancelled = true;
        for (var p : ps) {
            if (p.getState() == ParticipantTxState.CANCELLED) continue;
            try {
                tx.beforeCall(txId, token, p.getId(), GlobalTxState.CANCELLING);
                client.callCancel(txId, p);
                tx.markParticipant(txId, token, p.getId(), GlobalTxState.CANCELLING, ParticipantTxState.CANCELLED, null);
            } catch (ParticipantCallException ex) {
                if (ex.isConflict()) {
                    return tx.failParticipant(txId, token, p.getId(), GlobalTxState.CANCELLING,
                            "Cancel rejected with 409 by " + p.getParticipant() + ": " + summarize(ex));
                }
                log.warn("[cancel] failed (will retry via recovery) participant={} cause={}",
                        p.getParticipant(), ex.getMessage());
                tx.markParticipantAttempted(txId, token, p.getId(), GlobalTxState.CANCELLING, summarize(ex));
                allCancelled = false;
            }
        }
        if (allCancelled) {
            return tx.transitionGlobal(txId, token, GlobalTxState.CANCELLED, null);
        }
        return GlobalTxState.CANCELLING;
    }

    private boolean completeRegistration(List<TransactionParticipant> ps) {
        return ps.size() == 3 && ps.stream().map(TransactionParticipant::getParticipant)
                .collect(java.util.stream.Collectors.toSet()).equals(java.util.Set.of("inventory", "payment", "order"));
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
                body.put("amount", req.amount().setScale(2, java.math.RoundingMode.UNNECESSARY));
            }
            case "order" -> {
                body.put("customerId", req.customerId());
                body.put("sku", req.sku());
                body.put("qty", req.qty());
                body.put("amount", req.amount().setScale(2, java.math.RoundingMode.UNNECESSARY));
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
