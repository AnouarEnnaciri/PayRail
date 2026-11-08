package com.payrail.backend.transfer;

import com.payrail.backend.account.Account;
import com.payrail.backend.account.AccountRepository;
import com.payrail.backend.account.AccountStatus;
import com.payrail.backend.account.AccountType;
import com.payrail.backend.ledger.LedgerEntry;
import com.payrail.backend.ledger.LedgerPostingService;
import com.payrail.backend.ledger.LedgerTransaction;
import com.payrail.backend.outbox.OutboxEventWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class ExternalReservationService {
    public record ReservationResult(UUID transferId, UUID settlementRequestId, String status) { }

    private final JdbcTemplate jdbc;
    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final LedgerPostingService ledger;
    private final OutboxEventWriter outbox;

    public ExternalReservationService(JdbcTemplate jdbc, AccountRepository accounts,
                                      TransferRepository transfers, LedgerPostingService ledger,
                                      OutboxEventWriter outbox) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.transfers = transfers;
        this.ledger = ledger;
        this.outbox = outbox;
    }

    @Transactional
    public ReservationResult reserve(UUID senderId, String destinationReference,
                                     long amountMinor, String idempotencyKey) {
        if (senderId == null) throw new IllegalArgumentException("Sender is required");
        if (destinationReference == null || destinationReference.isBlank()
                || destinationReference.length() > 128) {
            throw new IllegalArgumentException("Invalid destination reference");
        }
        if (amountMinor <= 0) throw new IllegalArgumentException("Amount must be positive");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("Invalid idempotency key");
        }

        String requestHash = hash(senderId, destinationReference, amountMinor);
        int inserted = jdbc.update("""
            INSERT INTO transfer_idempotency (actor_id, idempotency_key, request_hash)
            VALUES (?, ?, ?)
            ON CONFLICT (actor_id, idempotency_key) DO NOTHING
            """, senderId, idempotencyKey, requestHash);

        if (inserted == 0) {
            StoredRequest stored = jdbc.queryForObject("""
                SELECT request_hash, transfer_id FROM transfer_idempotency
                WHERE actor_id = ? AND idempotency_key = ?
                """, (rs, row) -> new StoredRequest(rs.getString("request_hash"),
                        rs.getObject("transfer_id", UUID.class)), senderId, idempotencyKey);
            if (stored == null || !requestHash.equals(stored.hash())) {
                throw new IdempotencyConflictException("Idempotency key already used for another request");
            }
            if (stored.transferId() == null) {
                throw new IllegalStateException("Transfer result missing for idempotent request");
            }
            Transfer transfer = transfers.findById(stored.transferId()).orElseThrow();
            if (!"EXTERNAL".equals(transfer.getTransferType())) {
                throw new IdempotencyConflictException("Idempotency key belongs to a different transfer type");
            }
            return result(transfer);
        }

        // The first authoritative account read is SELECT ... FOR UPDATE.
        Account sender = accounts.findByIdForUpdate(senderId)
                .orElseThrow(() -> new IllegalArgumentException("Sender account not found"));
        if (sender.getAccountType() != AccountType.CUSTOMER || sender.getStatus() != AccountStatus.ACTIVE
                || !"MAD".equals(sender.getCurrency())) {
            throw new IllegalStateException("Sender must be an active MAD customer account");
        }
        Account clearing = accounts.findByReference("SETTLEMENT_CLEARING")
                .orElseThrow(() -> new IllegalStateException("SETTLEMENT_CLEARING account missing"));
        if (clearing.getAccountType() != AccountType.SYSTEM
                || clearing.getStatus() != AccountStatus.ACTIVE
                || !"MAD".equals(clearing.getCurrency())) {
            throw new IllegalStateException("Invalid SETTLEMENT_CLEARING account");
        }

        sender.debit(amountMinor);
        UUID settlementRequestId = UUID.randomUUID();
        Transfer transfer = Transfer.pendingExternal(senderId, destinationReference,
                amountMinor, settlementRequestId);
        transfers.saveAndFlush(transfer); // FK target exists before outbox SQL.

        LedgerTransaction tx = LedgerTransaction.externalReservation(transfer.getId());
        ledger.post(tx, List.of(
                LedgerEntry.create(tx.getId(), sender.getId(), -amountMinor),
                LedgerEntry.create(tx.getId(), clearing.getId(), amountMinor)
        ));
        transfers.flush();
        outbox.writeSettlementRequested(transfer.getId(), settlementRequestId,
                amountMinor, destinationReference);
        jdbc.update("""
            UPDATE transfer_idempotency SET transfer_id = ?
            WHERE actor_id = ? AND idempotency_key = ?
            """, transfer.getId(), senderId, idempotencyKey);
        return result(transfer);
    }

    private static ReservationResult result(Transfer transfer) {
        return new ReservationResult(transfer.getId(), transfer.getSettlementRequestId(),
                transfer.getStatus());
    }

    private static String hash(UUID senderId, String destination, long amountMinor) {
        String canonical = "EXTERNAL\n" + senderId + "\n" + amountMinor + "\n"
                + destination.length() + ":" + destination;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not supported", e);
        }
    }

    private record StoredRequest(String hash, UUID transferId) { }
}
