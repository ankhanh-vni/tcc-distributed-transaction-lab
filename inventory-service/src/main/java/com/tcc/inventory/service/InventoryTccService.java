package com.tcc.inventory.service;

import com.tcc.common.tcc.ParticipantState;
import com.tcc.common.tcc.TccErrorCode;
import com.tcc.common.tcc.TccException;
import com.tcc.inventory.api.dto.ReserveRequest;
import com.tcc.inventory.domain.InventoryReservation;
import com.tcc.inventory.domain.Product;
import com.tcc.inventory.repo.InventoryReservationRepository;
import com.tcc.inventory.repo.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class InventoryTccService {

    @PersistenceContext
    private EntityManager entityManager;

    // Row locks cannot protect a missing txId. The transaction-scoped lock covers
    // the first read through commit, including preventive Cancel and concurrent Try.
    private void lockTransaction(UUID txId) {
        long key = txId.getMostSignificantBits() ^ txId.getLeastSignificantBits();
        entityManager.createNativeQuery("select 1 from pg_advisory_xact_lock(:key)")
                .setParameter("key", key).getSingleResult();
    }

    private static final Logger log = LoggerFactory.getLogger(InventoryTccService.class);

    private final ProductRepository products;
    private final InventoryReservationRepository reservations;

    public InventoryTccService(ProductRepository products, InventoryReservationRepository reservations) {
        this.products = products;
        this.reservations = reservations;
    }

    /**
     * Try: business-locks stock. Idempotent on tx_id.
     */
    @Transactional
    public ParticipantState tryReserve(UUID txId, ReserveRequest req) {
        lockTransaction(txId);
        var existing = reservations.lockById(txId);
        if (existing.isPresent()) {
            return switch (existing.get().getState()) {
                case TRIED, CONFIRMED -> existing.get().getState();
                case CANCELLED -> throw new TccException(TccErrorCode.CONFIRM_AFTER_CANCEL,
                        "Try after preventive Cancel: tx already cancelled tx=" + txId);
                case INIT -> throw new IllegalStateException("inventory_reservation should never be in INIT state");
            };
        }
        Product product = products.lockBySku(req.sku())
                .orElseThrow(() -> new TccException(TccErrorCode.BUSINESS_PRECONDITION_FAILED,
                        "unknown sku=" + req.sku()));
        product.reserve(req.qty());
        reservations.save(new InventoryReservation(txId, product.getId(), req.qty(), ParticipantState.TRIED));
        log.info("[try] reserved sku={} qty={} tx={}", req.sku(), req.qty(), txId);
        return ParticipantState.TRIED;
    }

    /**
     * Confirm: applies the already-reserved effect. Idempotent. Rejects Confirm-after-Cancel.
     */
    @Transactional
    public ParticipantState confirm(UUID txId) {
        lockTransaction(txId);
        var existing = reservations.lockById(txId)
                .orElseThrow(() -> new TccException(TccErrorCode.UNKNOWN_TX,
                        "cannot confirm unknown tx=" + txId));
        switch (existing.getState()) {
            case CONFIRMED:
                return ParticipantState.CONFIRMED;
            case CANCELLED:
                throw new TccException(TccErrorCode.CONFIRM_AFTER_CANCEL,
                        "Confirm after Cancel: tx=" + txId);
            case TRIED:
                Product product = products.lockById(existing.getProductId()).orElseThrow();
                product.confirmReservation(existing.getQty());
                existing.setState(ParticipantState.CONFIRMED);
                log.info("[confirm] tx={} sku={} qty={}", txId, product.getSku(), existing.getQty());
                return ParticipantState.CONFIRMED;
            case INIT:
            default:
                throw new IllegalStateException("unexpected state: " + existing.getState());
        }
    }

    /**
     * Cancel: releases reservation. Idempotent. Rejects Cancel-after-Confirm.
     * Inserts a "preventive tombstone" if no Try ever arrived, to defeat a late Try.
     */
    @Transactional
    public ParticipantState cancel(UUID txId) {
        lockTransaction(txId);
        var existing = reservations.lockById(txId);
        if (existing.isEmpty()) {
            reservations.save(new InventoryReservation(txId, /*productId*/ null, 0, ParticipantState.CANCELLED));
            log.info("[cancel] preventive tombstone written tx={}", txId);
            return ParticipantState.CANCELLED;
        }
        switch (existing.get().getState()) {
            case CANCELLED:
                return ParticipantState.CANCELLED;
            case CONFIRMED:
                throw new TccException(TccErrorCode.CANCEL_AFTER_CONFIRM,
                        "Cancel after Confirm: tx=" + txId);
            case TRIED:
                Product product = products.lockById(existing.get().getProductId()).orElseThrow();
                product.releaseReservation(existing.get().getQty());
                existing.get().setState(ParticipantState.CANCELLED);
                log.info("[cancel] released sku={} qty={} tx={}", product.getSku(), existing.get().getQty(), txId);
                return ParticipantState.CANCELLED;
            case INIT:
            default:
                throw new IllegalStateException("unexpected state: " + existing.get().getState());
        }
    }

    @Transactional(readOnly = true)
    public ParticipantState getState(UUID txId) {
        return reservations.findById(txId).map(InventoryReservation::getState)
                .orElseThrow(() -> new TccException(TccErrorCode.UNKNOWN_TX, "unknown tx=" + txId));
    }
}
