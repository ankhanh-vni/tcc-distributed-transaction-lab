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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class PaymentTccService {

    private static final Logger log = LoggerFactory.getLogger(PaymentTccService.class);

    private final AccountRepository accounts;
    private final PaymentAuthorizationRepository authorizations;

    public PaymentTccService(AccountRepository accounts, PaymentAuthorizationRepository authorizations) {
        this.accounts = accounts;
        this.authorizations = authorizations;
    }

    @Transactional
    public ParticipantState tryAuthorize(UUID txId, AuthorizeRequest req) {
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
        try {
            authorizations.save(new PaymentAuthorization(txId, account.getId(), req.amount(), ParticipantState.TRIED));
        } catch (DataIntegrityViolationException duplicate) {
            var winner = authorizations.lockById(txId).orElseThrow();
            log.info("[try] concurrent retry resolved: tx={} state={}", txId, winner.getState());
            return winner.getState();
        }
        log.info("[try] frozen amount={} customer={} tx={}", req.amount(), req.customerId(), txId);
        return ParticipantState.TRIED;
    }

    @Transactional
    public ParticipantState confirm(UUID txId) {
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
                Account account = accounts.findById(existing.getAccountId()).orElseThrow();
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
        var existing = authorizations.lockById(txId);
        if (existing.isEmpty()) {
            try {
                authorizations.save(new PaymentAuthorization(txId, null, BigDecimal.ZERO, ParticipantState.CANCELLED));
                log.info("[cancel] preventive tombstone written tx={}", txId);
            } catch (DataIntegrityViolationException race) {
                var racer = authorizations.lockById(txId).orElseThrow();
                if (racer.getState() == ParticipantState.CONFIRMED) {
                    throw new TccException(TccErrorCode.CANCEL_AFTER_CONFIRM,
                            "Cancel after Confirm: tx=" + txId);
                }
                return racer.getState();
            }
            return ParticipantState.CANCELLED;
        }
        switch (existing.get().getState()) {
            case CANCELLED:
                return ParticipantState.CANCELLED;
            case CONFIRMED:
                throw new TccException(TccErrorCode.CANCEL_AFTER_CONFIRM,
                        "Cancel after Confirm: tx=" + txId);
            case TRIED:
                Account account = accounts.findById(existing.get().getAccountId()).orElseThrow();
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
