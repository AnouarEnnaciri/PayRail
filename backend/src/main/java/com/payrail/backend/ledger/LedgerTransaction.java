package com.payrail.backend.ledger;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_transactions")
public class LedgerTransaction {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "transfer_id")
    private UUID transferId;

    @Enumerated(EnumType.STRING)
    @Column(name = "effect_type", nullable = false, length = 64)
    private LedgerEffectType effectType;

    @Column(name = "reversal_of_transaction_id")
    private UUID reversalOfTransactionId;

    @Column(name = "funding_reference", length = 128)
    private String fundingReference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerTransaction() {
        // Required by JPA
    }

    public static LedgerTransaction initialFunding(String fundingReference) {
        if (fundingReference == null || fundingReference.isBlank()) {
            throw new IllegalArgumentException("Funding reference is required");
        }

        LedgerTransaction transaction = new LedgerTransaction();
        transaction.id = UUID.randomUUID();
        transaction.effectType = LedgerEffectType.INITIAL_FUNDING;
        transaction.fundingReference = fundingReference;

        return transaction;
    }

    public static LedgerTransaction internalTransfer(UUID transferId) {
        if (transferId == null) {
            throw new IllegalArgumentException("Transfer id is required");
        }

        LedgerTransaction transaction = new LedgerTransaction();
        transaction.id = UUID.randomUUID();
        transaction.transferId = transferId;
        transaction.effectType = LedgerEffectType.INTERNAL_TRANSFER;

        return transaction;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public LedgerEffectType getEffectType() {
        return effectType;
    }

    public UUID getReversalOfTransactionId() {
        return reversalOfTransactionId;
    }

    public String getFundingReference() {
        return fundingReference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}