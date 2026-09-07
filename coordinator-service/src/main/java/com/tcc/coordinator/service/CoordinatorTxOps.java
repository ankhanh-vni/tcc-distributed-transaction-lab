package com.tcc.coordinator.service;

import com.tcc.coordinator.api.dto.PlaceOrderRequest;
import com.tcc.coordinator.config.TccProperties;
import com.tcc.coordinator.domain.*;
import com.tcc.coordinator.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

@Component
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class CoordinatorTxOps {
    private static final Logger log = LoggerFactory.getLogger(CoordinatorTxOps.class);
    private final GlobalTransactionRepository globals;
    private final TransactionParticipantRepository participants;
    private final TccProperties props;
    private final JdbcTemplate jdbc;

    public CoordinatorTxOps(GlobalTransactionRepository globals, TransactionParticipantRepository participants,
                            TccProperties props, JdbcTemplate jdbc) {
        this.globals = globals;
        this.participants = participants;
        this.props = props;
        this.jdbc = jdbc;
    }

    public void seedTransaction(UUID txId, PlaceOrderRequest req,
                                 BiFunction<UUID, String, String> payloadFor) {
        var businessKey = "order:" + req.customerId() + ":" + req.sku();
        globals.save(new GlobalTransaction(txId, businessKey, GlobalTxState.STARTED));
        var inv = props.getParticipants().get("inventory");
        var pay = props.getParticipants().get("payment");
        var ord = props.getParticipants().get("order");
        if (inv == null || pay == null || ord == null) {
            throw new IllegalStateException("missing participant config: inventory/payment/order");
        }
        participants.save(new TransactionParticipant(txId, "inventory", inv.getBaseUrl(), inv.getResourcePath(),
                ParticipantTxState.PENDING, payloadFor.apply(txId, "inventory")));
        participants.save(new TransactionParticipant(txId, "payment", pay.getBaseUrl(), pay.getResourcePath(),
                ParticipantTxState.PENDING, payloadFor.apply(txId, "payment")));
        participants.save(new TransactionParticipant(txId, "order", ord.getBaseUrl(), ord.getResourcePath(),
                ParticipantTxState.PENDING, payloadFor.apply(txId, "order")));
        log.info("[seed] tx={} business={} participants=3", txId, businessKey);
    }


    public record SeedResult(UUID txId, boolean created, boolean terminal) {}

    public SeedResult seedOrGet(String key, String fingerprint, PlaceOrderRequest req,
                                BiFunction<UUID, String, String> payloadFor) {
        if (key != null) {
            // Existing mappings are immutable and retained indefinitely. Read them
            // without queuing behind first-use locks; only creation needs exclusion.
            var found = existingRequest(key, fingerprint);
            if (found != null) return found;
            jdbc.queryForObject("select 1 from pg_advisory_xact_lock(27182, hashtext(?))", Integer.class, key);
            found = existingRequest(key, fingerprint);
            if (found != null) return found;
        }
        UUID txId = UUID.randomUUID();
        // Intentional self-call: creation and key reservation belong to this same
        // REQUIRES_NEW transaction, with no RPC until it has committed.
        seedTransaction(txId, req, payloadFor);
        if (key != null) {
            globals.flush();
            jdbc.update("insert into order_idempotency(idempotency_key, request_fingerprint, tx_id) values (?, ?, ?)",
                    key, fingerprint, txId);
        }
        return new SeedResult(txId, true, false);
    }

    private SeedResult existingRequest(String key, String fingerprint) {
        var rows = jdbc.query("""
                select k.tx_id, k.request_fingerprint, g.state from order_idempotency k
                join global_transaction g on g.tx_id=k.tx_id where k.idempotency_key=?
                """, (rs, row) -> new StoredRequest(rs.getObject("tx_id", UUID.class),
                        rs.getString("request_fingerprint"), GlobalTxState.valueOf(rs.getString("state"))), key);
        if (rows.isEmpty()) return null;
        var stored = rows.getFirst();
        if (!stored.fingerprint().equals(fingerprint)) throw new IdempotencyConflictException();
        return new SeedResult(stored.txId(), false, stored.state().isTerminal());
    }

    private record StoredRequest(UUID txId, String fingerprint, GlobalTxState state) {}

    public boolean acquire(UUID txId, UUID token) {
        var g = globals.lockById(txId).orElseThrow();
        var now = now();
        if (g.getState().isTerminal() || (g.getLeaseUntil() != null && g.getLeaseUntil().isAfter(now))) return false;
        g.lease(token, now.plusNanos(props.getRecovery().getLeaseMs() * 1_000_000));
        g.incrementAttempt();
        return true;
    }

    public void release(UUID txId, UUID token) {
        var g = globals.lockById(txId).orElseThrow();
        if (token.equals(g.getDriverToken())) g.lease(null, null);
    }

    private OffsetDateTime now() {
        return jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
    }

    private GlobalTransaction owned(UUID txId, UUID token) {
        var g = globals.lockById(txId).orElseThrow();
        var now = now();
        if (!token.equals(g.getDriverToken()) || g.getLeaseUntil() == null || !g.getLeaseUntil().isAfter(now)) {
            throw new StaleDriverException();
        }
        g.lease(token, now.plusNanos(props.getRecovery().getLeaseMs() * 1_000_000));
        return g;
    }

    public GlobalTxState transitionGlobal(UUID txId, UUID token, GlobalTxState target, String error) {
        var g = owned(txId, token);
        if (target == GlobalTxState.CONFIRMING) requireParticipants(txId, ParticipantTxState.TRIED);
        if (target == GlobalTxState.CONFIRMED) requireParticipants(txId, ParticipantTxState.CONFIRMED);
        if (target == GlobalTxState.CANCELLED) requireParticipants(txId, ParticipantTxState.CANCELLED);
        return transition(g, target, error);
    }

    private void requireParticipants(UUID txId, ParticipantTxState state) {
        var ps = participants.findByTxIdOrderByIdAsc(txId);
        if (ps.size() != 3 || !ps.stream().map(TransactionParticipant::getParticipant).collect(Collectors.toSet())
                .equals(Set.of("inventory", "payment", "order")) || ps.stream().anyMatch(p -> p.getState() != state)) {
            throw new IllegalStateException("Incomplete participant outcome for " + state);
        }
    }

    private GlobalTxState transition(GlobalTransaction g, GlobalTxState target, String error) {
        if (!g.getState().canTransitionTo(target)) {
            throw new IllegalStateException("Illegal global transition " + g.getState() + " -> " + target);
        }
        log.info("[state] tx={} {} -> {}", g.getTxId(), g.getState(), target);
        g.setState(target);
        g.setLastError(error);
        return target;
    }

    private void requireUpdated(int rows) {
        if (rows != 1) throw new IllegalStateException("Participant missing, belongs to another transaction, or has an incompatible state");
    }

    private void requirePhase(GlobalTransaction g, GlobalTxState phase) {
        if (g.getState() != phase || !phase.isInFlight()) throw new StaleDriverException();
    }

    // Committed before RPC; also renews ownership without holding a DB transaction over HTTP.
    public void beforeCall(UUID txId, UUID token, Long id, GlobalTxState phase) {
        requirePhase(owned(txId, token), phase);
        requireUpdated(jdbc.update("update transaction_participant set last_attempted_at=clock_timestamp() where tx_id=? and id=?",
                txId, id));
    }

    public void markParticipant(UUID txId, UUID token, Long id, GlobalTxState phase,
                                ParticipantTxState state, String error) {
        requirePhase(owned(txId, token), phase);
        String sourcePredicate = switch (phase) {
            case TRYING -> state == ParticipantTxState.TRIED ? "state='PENDING'" : null;
            case CONFIRMING -> state == ParticipantTxState.CONFIRMED ? "state='TRIED'" : null;
            case CANCELLING -> state == ParticipantTxState.CANCELLED ? "state<>'CONFIRMED'" : null;
            default -> null;
        };
        if (sourcePredicate == null) throw new IllegalStateException("Illegal participant target " + state);
        // The global row is already locked and ownership checked in this same
        // transaction. The predicate preserves the previous-state guard in SQL.
        requireUpdated(jdbc.update("update transaction_participant set state=?, last_error=? where tx_id=? and id=? and " + sourcePredicate,
                state.name(), error, txId, id));
    }

    public void markParticipantAttempted(UUID txId, UUID token, Long id, GlobalTxState phase, String error) {
        requirePhase(owned(txId, token), phase);
        requireUpdated(jdbc.update("update transaction_participant set last_error=? where tx_id=? and id=?", error, txId, id));
    }

    // Failure evidence and the durable decision must commit or roll back together.
    public GlobalTxState failParticipant(UUID txId, UUID token, Long id, GlobalTxState phase, String error) {
        var g = owned(txId, token);
        requirePhase(g, phase);
        requireUpdated(jdbc.update("update transaction_participant set state='FAILED', last_error=? where tx_id=? and id=?", error, txId, id));
        return transition(g, phase == GlobalTxState.TRYING ? GlobalTxState.CANCELLING : GlobalTxState.HEURISTIC, error);
    }
}
