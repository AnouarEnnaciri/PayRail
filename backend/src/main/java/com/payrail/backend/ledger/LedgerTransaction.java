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

    protected LedgerTransaction() { }

    public static LedgerTransaction initialFunding(String fundingReference) {
        if (fundingReference == null || fundingReference.isBlank()) {
            throw new IllegalArgumentException("Funding reference is required");
        }
        LedgerTransaction tx = new LedgerTransaction();
        tx.id = UUID.randomUUID();
        tx.effectType = LedgerEffectType.INITIAL_FUNDING;
        tx.fundingReference = fundingReference;
        return tx;
    }

    public static LedgerTransaction internalTransfer(UUID transferId) {
        return forTransfer(transferId, LedgerEffectType.INTERNAL_TRANSFER);
    }

    public static LedgerTransaction externalReservation(UUID transferId) {
        return forTransfer(transferId, LedgerEffectType.EXTERNAL_RESERVATION);
    }

    public static LedgerTransaction externalSettlementFinalization(UUID transferId) {
        return forTransfer(transferId, LedgerEffectType.EXTERNAL_SETTLEMENT_FINALIZATION);
    }

    public static LedgerTransaction externalCompensation(UUID transferId) {
        return forTransfer(transferId, LedgerEffectType.EXTERNAL_COMPENSATION);
    }

    private static LedgerTransaction forTransfer(UUID transferId, LedgerEffectType type) {
        if (transferId == null) {
            throw new IllegalArgumentException("Transfer id is required");
        }
        LedgerTransaction tx = new LedgerTransaction();
        tx.id = UUID.randomUUID();
        tx.transferId = transferId;
        tx.effectType = type;
        return tx;
    }

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getTransferId() { return transferId; }
    public LedgerEffectType getEffectType() { return effectType; }
    public UUID getReversalOfTransactionId() { return reversalOfTransactionId; }
    public String getFundingReference() { return fundingReference; }
    public Instant getCreatedAt() { return createdAt; }
}
