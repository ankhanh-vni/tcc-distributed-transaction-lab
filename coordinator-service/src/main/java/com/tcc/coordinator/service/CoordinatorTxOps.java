package com.tcc.coordinator.service;

import com.tcc.coordinator.api.dto.PlaceOrderRequest;
import com.tcc.coordinator.config.TccProperties;
import com.tcc.coordinator.domain.GlobalTransaction;
import com.tcc.coordinator.domain.GlobalTxState;
import com.tcc.coordinator.domain.ParticipantTxState;
import com.tcc.coordinator.domain.TransactionParticipant;
import com.tcc.coordinator.repo.GlobalTransactionRepository;
import com.tcc.coordinator.repo.TransactionParticipantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Transactional units of work for the coordinator. Pulled into a separate component
 * because Spring's proxy-based @Transactional does not apply to self-invocations.
 */
@Component
public class CoordinatorTxOps {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorTxOps.class);

    private final GlobalTransactionRepository globals;
    private final TransactionParticipantRepository participants;
    private final TccProperties props;

    public CoordinatorTxOps(GlobalTransactionRepository globals,
                             TransactionParticipantRepository participants,
                             TccProperties props) {
        this.globals = globals;
        this.participants = participants;
        this.props = props;
    }

    @Transactional
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

    @Transactional
    public GlobalTxState transitionStartedToTrying(UUID txId) {
        var g = globals.lockById(txId).orElseThrow();
        if (g.getState() == GlobalTxState.STARTED) {
            g.setState(GlobalTxState.TRYING);
            log.info("[state] tx={} STARTED -> TRYING", txId);
        }
        return g.getState();
    }

    @Transactional
    public GlobalTxState transitionGlobal(UUID txId, GlobalTxState target, String error) {
        var g = globals.lockById(txId).orElseThrow();
        log.info("[state] tx={} {} -> {}", txId, g.getState(), target);
        g.setState(target);
        if (error != null) g.setLastError(error);
        g.incrementAttempt();
        return target;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markParticipant(Long participantRowId, ParticipantTxState state, String error) {
        var p = participants.findById(participantRowId).orElseThrow();
        p.setState(state);
        p.markAttempted();
        p.setLastError(error);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markParticipantAttempted(Long participantRowId, String error) {
        var p = participants.findById(participantRowId).orElseThrow();
        p.markAttempted();
        p.setLastError(error);
    }
}
