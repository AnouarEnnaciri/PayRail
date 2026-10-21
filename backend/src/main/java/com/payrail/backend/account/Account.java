package com.payrail.backend.account;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String reference;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 16)
    private AccountType accountType;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "balance_minor")
    private Long balanceMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;


    protected Account() {
        // Required by JPA
    }


    public static Account customer(String reference, UUID ownerId) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Account reference is required");
        }

        if (ownerId == null) {
            throw new IllegalArgumentException("Customer account requires an owner");
        }

        Account account = new Account();
        account.id = UUID.randomUUID();
        account.reference = reference;
        account.ownerId = ownerId;
        account.accountType = AccountType.CUSTOMER;
        account.currency = "MAD";
        account.balanceMinor = 0L;
        account.status = AccountStatus.ACTIVE;

        return account;
    }


    public static Account system(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Account reference is required");
        }

        Account account = new Account();
        account.id = UUID.randomUUID();
        account.reference = reference;
        account.ownerId = null;
        account.accountType = AccountType.SYSTEM;
        account.currency = "MAD";
        account.balanceMinor = null;
        account.status = AccountStatus.ACTIVE;

        return account;
    }


    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }


    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }


    public UUID getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public String getCurrency() {
        return currency;
    }

    public Long getBalanceMinor() {
        return balanceMinor;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}