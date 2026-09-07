package com.tcc.coordinator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "global_transaction")
public class GlobalTransaction {

    @Id
    @Column(name = "tx_id")
    private UUID txId;

    @Column(name = "business_key")
    private String businessKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private GlobalTxState state;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected GlobalTransaction() {
    }

    public GlobalTransaction(UUID txId, String businessKey, GlobalTxState state) {
        this.txId = txId;
        this.businessKey = businessKey;
        this.state = state;
    }

    @Column(name = "driver_token")
    private UUID driverToken;

    @Column(name = "lease_until")
    private OffsetDateTime leaseUntil;

    public UUID getDriverToken() { return driverToken; }
    public OffsetDateTime getLeaseUntil() { return leaseUntil; }
    public void lease(UUID token, OffsetDateTime until) { driverToken = token; leaseUntil = until; }

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
    public String getBusinessKey() { return businessKey; }
    public GlobalTxState getState() { return state; }
    public void setState(GlobalTxState state) { this.state = state; }
    public int getAttemptCount() { return attemptCount; }
    public void incrementAttempt() { this.attemptCount++; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
