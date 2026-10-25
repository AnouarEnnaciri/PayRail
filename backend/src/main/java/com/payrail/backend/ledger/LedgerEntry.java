package com.payrail.backend.ledger;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "ledger_transaction_id", nullable = false)
    private UUID ledgerTransactionId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "signed_amount_minor", nullable = false)
    private long signedAmountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
        // Required by JPA
    }

    public static LedgerEntry create(
            UUID ledgerTransactionId,
            UUID accountId,
            long signedAmountMinor
    ) {
        if (ledgerTransactionId == null) {
            throw new IllegalArgumentException("Ledger transaction id is required");
        }

        if (accountId == null) {
            throw new IllegalArgumentException("Account id is required");
        }

        if (signedAmountMinor == 0) {
            throw new IllegalArgumentException("Ledger amount cannot be zero");
        }

        LedgerEntry entry = new LedgerEntry();
        entry.id = UUID.randomUUID();
        entry.ledgerTransactionId = ledgerTransactionId;
        entry.accountId = accountId;
        entry.signedAmountMinor = signedAmountMinor;
        entry.currency = "MAD";

        return entry;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getLedgerTransactionId() {
        return ledgerTransactionId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public long getSignedAmountMinor() {
        return signedAmountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}