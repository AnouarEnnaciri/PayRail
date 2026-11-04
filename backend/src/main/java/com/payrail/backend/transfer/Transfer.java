
package com.payrail.backend.transfer;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    private UUID id;

    @Column(name = "sender_account_id", nullable = false)
    private UUID senderAccountId;

    @Column(name = "receiver_account_id", nullable = false)
    private UUID receiverAccountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Transfer() {
        // Required by JPA
    }

    public static Transfer completed(
            UUID senderId,
            UUID receiverId,
            long amountMinor
    ) {
        if (senderId == null || receiverId == null) {
            throw new IllegalArgumentException("Both accounts are required");
        }

        if (senderId.equals(receiverId)) {
            throw new IllegalArgumentException("Cannot transfer to the same account");
        }

        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Transfer amount must be positive");
        }

        Transfer transfer = new Transfer();
        transfer.id = UUID.randomUUID();
        transfer.senderAccountId = senderId;
        transfer.receiverAccountId = receiverId;
        transfer.amountMinor = amountMinor;
        transfer.status = "COMPLETED";

        return transfer;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }
}
