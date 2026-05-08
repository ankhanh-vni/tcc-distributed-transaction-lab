package com.tcc.coordinator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "transaction_participant")
public class TransactionParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tx_id", nullable = false)
    private UUID txId;

    @Column(nullable = false, length = 64)
    private String participant;

    @Column(name = "base_url", nullable = false, length = 256)
    private String baseUrl;

    @Column(name = "resource_path", nullable = false, length = 256)
    private String resourcePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ParticipantTxState state;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "last_attempted_at")
    private OffsetDateTime lastAttemptedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    protected TransactionParticipant() {
    }

    public TransactionParticipant(UUID txId, String participant, String baseUrl,
                                   String resourcePath, ParticipantTxState state, String payloadJson) {
        this.txId = txId;
        this.participant = participant;
        this.baseUrl = baseUrl;
        this.resourcePath = resourcePath;
        this.state = state;
        this.payloadJson = payloadJson;
    }

    public Long getId() { return id; }
    public UUID getTxId() { return txId; }
    public String getParticipant() { return participant; }
    public String getBaseUrl() { return baseUrl; }
    public String getResourcePath() { return resourcePath; }
    public ParticipantTxState getState() { return state; }
    public void setState(ParticipantTxState state) { this.state = state; }
    public String getPayloadJson() { return payloadJson; }
    public OffsetDateTime getLastAttemptedAt() { return lastAttemptedAt; }
    public void markAttempted() { this.lastAttemptedAt = OffsetDateTime.now(); }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
