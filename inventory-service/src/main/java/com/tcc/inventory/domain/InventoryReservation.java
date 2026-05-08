package com.tcc.inventory.domain;

import com.tcc.common.tcc.ParticipantState;
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
@Table(name = "inventory_reservation")
public class InventoryReservation {

    @Id
    @Column(name = "tx_id")
    private UUID txId;

    @Column(name = "product_id")
    private Long productId;

    @Column(nullable = false)
    private int qty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ParticipantState state;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected InventoryReservation() {
    }

    public InventoryReservation(UUID txId, Long productId, int qty, ParticipantState state) {
        this.txId = txId;
        this.productId = productId;
        this.qty = qty;
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

    public UUID getTxId() {
        return txId;
    }

    public Long getProductId() {
        return productId;
    }

    public int getQty() {
        return qty;
    }

    public ParticipantState getState() {
        return state;
    }

    public void setState(ParticipantState state) {
        this.state = state;
    }
}
