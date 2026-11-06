
package com.payrail.backend.transfer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class IdempotentTransferService {

    private final JdbcTemplate jdbcTemplate;
    private final InternalTransferService transferService;
    private final TransferRepository transferRepository;

    public IdempotentTransferService(
            JdbcTemplate jdbcTemplate,
            InternalTransferService transferService,
            TransferRepository transferRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transferService = transferService;
        this.transferRepository = transferRepository;
    }

    @Transactional
    public UUID transfer(
            UUID senderId,
            UUID receiverId,
            long amountMinor,
            String idempotencyKey
    ) {
        if (senderId == null || receiverId == null) {
            throw new IllegalArgumentException("Both accounts are required");
        }

        if (idempotencyKey == null
                || idempotencyKey.isBlank()
                || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("Invalid idempotency key");
        }

        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }

        String hash = calculateHash(senderId, receiverId, amountMinor);

        int inserted = jdbcTemplate.update("""
                INSERT INTO transfer_idempotency
                    (actor_id, idempotency_key, request_hash)
                VALUES (?, ?, ?)
                ON CONFLICT (actor_id, idempotency_key) DO NOTHING
                """,
                senderId, idempotencyKey, hash
        );

        if (inserted == 0) {
            StoredRequest existing = jdbcTemplate.queryForObject("""
                    SELECT request_hash, transfer_id
                    FROM transfer_idempotency
                    WHERE actor_id = ? AND idempotency_key = ?
                    """,
                    (rs, rowNum) -> new StoredRequest(
                            rs.getString("request_hash"),
                            rs.getObject("transfer_id", UUID.class)
                    ),
                    senderId, idempotencyKey
            );

            if (!hash.equals(existing.hash())) {
                throw new IdempotencyConflictException(
                        "Idempotency key already used for another request"
                );
            }

            if (existing.transferId() == null) {
                throw new IllegalStateException(
                        "Transfer result is missing"
                );
            }

            return existing.transferId();
        }

        UUID transferId = transferService.transfer(
                senderId, receiverId, amountMinor
        );

        transferRepository.flush();

        jdbcTemplate.update("""
                UPDATE transfer_idempotency
                SET transfer_id = ?
                WHERE actor_id = ? AND idempotency_key = ?
                """,
                transferId, senderId, idempotencyKey
        );

        return transferId;
    }

    private String calculateHash(
            UUID senderId,
            UUID receiverId,
            long amountMinor
    ) {
        String request = senderId + "|" + receiverId + "|" + amountMinor;

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(
                    digest.digest(
                            request.getBytes(StandardCharsets.UTF_8)
                    )
            );

        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record StoredRequest(String hash, UUID transferId) {
    }
}
