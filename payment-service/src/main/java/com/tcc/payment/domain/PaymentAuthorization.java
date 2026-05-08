package com.tcc.payment.domain;

import com.tcc.common.tcc.ParticipantState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "payment_authorization")
public class PaymentAuthorization {

    @Id
    @Column(name = "tx_id")
    private UUID txId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ParticipantState state;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected PaymentAuthorization() {
    }

    public PaymentAuthorization(UUID txId, Long accountId, BigDecimal amount, ParticipantState state) {
        this.txId = txId;
        this.accountId = accountId;
        this.amount = amount;
        this.state = state;
    }

    @PrePersist
    void onInsert() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getTxId() { return txId; }
    public Long getAccountId() { return accountId; }
    public BigDecimal getAmount() { return amount; }
    public ParticipantState getState() { return state; }
    public void setState(ParticipantState state) { this.state = state; }
}
