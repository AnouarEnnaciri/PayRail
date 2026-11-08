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

    @Column(name = "receiver_account_id")
    private UUID receiverAccountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "transfer_type", nullable = false, length = 16)
    private String transferType;

    @Column(name = "destination_reference", length = 128)
    private String destinationReference;

    @Column(name = "settlement_request_id")
    private UUID settlementRequestId;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Transfer() { }

    public static Transfer completed(UUID senderId, UUID receiverId, long amountMinor) {
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
        transfer.transferType = "INTERNAL";
        transfer.status = "COMPLETED";
        return transfer;
    }

    public static Transfer pendingExternal(UUID senderId, String destinationReference,
                                           long amountMinor, UUID settlementRequestId) {
        if (senderId == null || settlementRequestId == null) {
            throw new IllegalArgumentException("Sender and settlement request IDs are required");
        }
        if (destinationReference == null || destinationReference.isBlank()
                || destinationReference.length() > 128) {
            throw new IllegalArgumentException("Invalid external destination reference");
        }
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Transfer amount must be positive");
        }
        Transfer transfer = new Transfer();
        transfer.id = UUID.randomUUID();
        transfer.senderAccountId = senderId;
        transfer.amountMinor = amountMinor;
        transfer.transferType = "EXTERNAL";
        transfer.destinationReference = destinationReference;
        transfer.settlementRequestId = settlementRequestId;
        transfer.status = "PENDING_SETTLEMENT";
        return transfer;
    }

    public void markSettled() {
        assertPendingExternal();
        status = "SETTLED";
    }

    public void markFailedAfterDefinitiveRejection() {
        assertPendingExternal();
        status = "FAILED";
    }

    private void assertPendingExternal() {
        if (!"EXTERNAL".equals(transferType) || !"PENDING_SETTLEMENT".equals(status)) {
            throw new IllegalStateException("Only pending external transfers can be finalized");
        }
    }

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getSenderAccountId() { return senderAccountId; }
    public UUID getReceiverAccountId() { return receiverAccountId; }
    public long getAmountMinor() { return amountMinor; }
    public String getTransferType() { return transferType; }
    public String getDestinationReference() { return destinationReference; }
    public UUID getSettlementRequestId() { return settlementRequestId; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
