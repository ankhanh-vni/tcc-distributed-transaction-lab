package com.tcc.payment.service;

import com.tcc.common.tcc.ParticipantState;
import com.tcc.common.tcc.TccErrorCode;
import com.tcc.common.tcc.TccException;
import com.tcc.payment.api.dto.AuthorizeRequest;
import com.tcc.payment.domain.Account;
import com.tcc.payment.domain.PaymentAuthorization;
import com.tcc.payment.repo.AccountRepository;
import com.tcc.payment.repo.PaymentAuthorizationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class PaymentTccService {

    @PersistenceContext
    private EntityManager entityManager;

    // Row locks cannot protect a missing txId. The transaction-scoped lock covers
    // the first read through commit, including preventive Cancel and concurrent Try.
    private void lockTransaction(UUID txId) {
        long key = txId.getMostSignificantBits() ^ txId.getLeastSignificantBits();
        entityManager.createNativeQuery("select 1 from pg_advisory_xact_lock(:key)")
                .setParameter("key", key).getSingleResult();
    }

    private static final Logger log = LoggerFactory.getLogger(PaymentTccService.class);

    private final AccountRepository accounts;
    private final PaymentAuthorizationRepository authorizations;

    public PaymentTccService(AccountRepository accounts, PaymentAuthorizationRepository authorizations) {
        this.accounts = accounts;
        this.authorizations = authorizations;
    }

    @Transactional
    public ParticipantState tryAuthorize(UUID txId, AuthorizeRequest req) {
        lockTransaction(txId);
        var existing = authorizations.lockById(txId);
        if (existing.isPresent()) {
            return switch (existing.get().getState()) {
                case TRIED, CONFIRMED -> existing.get().getState();
                case CANCELLED -> throw new TccException(TccErrorCode.CONFIRM_AFTER_CANCEL,
                        "Try after preventive Cancel: tx already cancelled tx=" + txId);
                case INIT -> throw new IllegalStateException("payment_authorization should never be in INIT state");
            };
        }
        Account account = accounts.lockByCustomerId(req.customerId())
                .orElseThrow(() -> new TccException(TccErrorCode.BUSINESS_PRECONDITION_FAILED,
                        "unknown customerId=" + req.customerId()));
        account.freeze(req.amount());
        authorizations.save(new PaymentAuthorization(txId, account.getId(), req.amount(), ParticipantState.TRIED));
        log.info("[try] frozen amount={} customer={} tx={}", req.amount(), req.customerId(), txId);
        return ParticipantState.TRIED;
    }

    @Transactional
    public ParticipantState confirm(UUID txId) {
        lockTransaction(txId);
        var existing = authorizations.lockById(txId)
                .orElseThrow(() -> new TccException(TccErrorCode.UNKNOWN_TX,
                        "cannot confirm unknown tx=" + txId));
        switch (existing.getState()) {
            case CONFIRMED:
                return ParticipantState.CONFIRMED;
            case CANCELLED:
                throw new TccException(TccErrorCode.CONFIRM_AFTER_CANCEL,
                        "Confirm after Cancel: tx=" + txId);
            case TRIED:
                Account account = accounts.lockById(existing.getAccountId()).orElseThrow();
                account.capture(existing.getAmount());
                existing.setState(ParticipantState.CONFIRMED);
                log.info("[confirm] captured amount={} customer={} tx={}",
                        existing.getAmount(), account.getCustomerId(), txId);
                return ParticipantState.CONFIRMED;
            case INIT:
            default:
                throw new IllegalStateException("unexpected state: " + existing.getState());
        }
    }

    @Transactional
    public ParticipantState cancel(UUID txId) {
        lockTransaction(txId);
        var existing = authorizations.lockById(txId);
        if (existing.isEmpty()) {
            authorizations.save(new PaymentAuthorization(txId, null, BigDecimal.ZERO, ParticipantState.CANCELLED));
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
                Account account = accounts.lockById(existing.get().getAccountId()).orElseThrow();
                account.unfreeze(existing.get().getAmount());
                existing.get().setState(ParticipantState.CANCELLED);
                log.info("[cancel] unfrozen amount={} customer={} tx={}",
                        existing.get().getAmount(), account.getCustomerId(), txId);
                return ParticipantState.CANCELLED;
            case INIT:
            default:
                throw new IllegalStateException("unexpected state: " + existing.get().getState());
        }
    }

    @Transactional(readOnly = true)
    public ParticipantState getState(UUID txId) {
        return authorizations.findById(txId).map(PaymentAuthorization::getState)
                .orElseThrow(() -> new TccException(TccErrorCode.UNKNOWN_TX, "unknown tx=" + txId));
    }
}
