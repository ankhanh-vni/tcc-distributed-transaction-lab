package com.tcc.order.service;

import com.tcc.common.tcc.ParticipantState;
import com.tcc.common.tcc.TccErrorCode;
import com.tcc.common.tcc.TccException;
import com.tcc.order.api.dto.CreateOrderRequest;
import com.tcc.order.domain.CustomerOrder;
import com.tcc.order.domain.OrderState;
import com.tcc.order.repo.CustomerOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class OrderTccService {

    private static final Logger log = LoggerFactory.getLogger(OrderTccService.class);

    private final CustomerOrderRepository orders;

    public OrderTccService(CustomerOrderRepository orders) {
        this.orders = orders;
    }

    /**
     * In this service we conflate the business row and the TCC log: tx_id IS the order id.
     * State machine: PENDING (after Try) -> CONFIRMED (after Confirm) | CANCELLED (after Cancel).
     */
    @Transactional
    public ParticipantState tryCreate(UUID txId, CreateOrderRequest req) {
        var existing = orders.lockById(txId);
        if (existing.isPresent()) {
            return switch (existing.get().getState()) {
                case PENDING -> ParticipantState.TRIED;
                case CONFIRMED -> ParticipantState.CONFIRMED;
                case CANCELLED -> throw new TccException(TccErrorCode.CONFIRM_AFTER_CANCEL,
                        "Try after preventive Cancel: tx already cancelled tx=" + txId);
            };
        }
        try {
            orders.save(new CustomerOrder(txId, req.customerId(), req.sku(), req.qty(), req.amount(), OrderState.PENDING));
        } catch (DataIntegrityViolationException race) {
            var winner = orders.lockById(txId).orElseThrow();
            log.info("[try] concurrent retry resolved: tx={} state={}", txId, winner.getState());
            return mapState(winner.getState());
        }
        log.info("[try] created pending order tx={} customer={} sku={}", txId, req.customerId(), req.sku());
        return ParticipantState.TRIED;
    }

    @Transactional
    public ParticipantState confirm(UUID txId) {
        var order = orders.lockById(txId)
                .orElseThrow(() -> new TccException(TccErrorCode.UNKNOWN_TX,
                        "cannot confirm unknown order tx=" + txId));
        switch (order.getState()) {
            case CONFIRMED:
                return ParticipantState.CONFIRMED;
            case CANCELLED:
                throw new TccException(TccErrorCode.CONFIRM_AFTER_CANCEL,
                        "Confirm after Cancel: tx=" + txId);
            case PENDING:
                order.setState(OrderState.CONFIRMED);
                log.info("[confirm] tx={}", txId);
                return ParticipantState.CONFIRMED;
            default:
                throw new IllegalStateException("unexpected state: " + order.getState());
        }
    }

    @Transactional
    public ParticipantState cancel(UUID txId) {
        var existing = orders.lockById(txId);
        if (existing.isEmpty()) {
            try {
                orders.save(new CustomerOrder(txId, "tombstone", "tombstone", 0, BigDecimal.ZERO, OrderState.CANCELLED));
                log.info("[cancel] preventive tombstone written tx={}", txId);
            } catch (DataIntegrityViolationException race) {
                var racer = orders.lockById(txId).orElseThrow();
                if (racer.getState() == OrderState.CONFIRMED) {
                    throw new TccException(TccErrorCode.CANCEL_AFTER_CONFIRM,
                            "Cancel after Confirm: tx=" + txId);
                }
                return mapState(racer.getState());
            }
            return ParticipantState.CANCELLED;
        }
        switch (existing.get().getState()) {
            case CANCELLED:
                return ParticipantState.CANCELLED;
            case CONFIRMED:
                throw new TccException(TccErrorCode.CANCEL_AFTER_CONFIRM,
                        "Cancel after Confirm: tx=" + txId);
            case PENDING:
                existing.get().setState(OrderState.CANCELLED);
                log.info("[cancel] tx={}", txId);
                return ParticipantState.CANCELLED;
            default:
                throw new IllegalStateException("unexpected state: " + existing.get().getState());
        }
    }

    @Transactional(readOnly = true)
    public ParticipantState getState(UUID txId) {
        return orders.findById(txId).map(o -> mapState(o.getState()))
                .orElseThrow(() -> new TccException(TccErrorCode.UNKNOWN_TX, "unknown tx=" + txId));
    }

    private static ParticipantState mapState(OrderState s) {
        return switch (s) {
            case PENDING -> ParticipantState.TRIED;
            case CONFIRMED -> ParticipantState.CONFIRMED;
            case CANCELLED -> ParticipantState.CANCELLED;
        };
    }
}
