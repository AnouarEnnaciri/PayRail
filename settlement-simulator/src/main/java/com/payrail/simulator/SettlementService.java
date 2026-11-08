package com.payrail.simulator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
class SettlementService {
    private final SettlementRepository repository;
    private final long settlementDelayMs;

    SettlementService(SettlementRepository repository,
                      @Value("${simulator.settlement-delay-ms}") long settlementDelayMs) {
        if (settlementDelayMs < 0) {
            throw new IllegalArgumentException("simulator.settlement-delay-ms must be >= 0");
        }
        this.repository = repository;
        this.settlementDelayMs = settlementDelayMs;
    }

    @Transactional
    public Submission submit(SubmitSettlementRequest request) {
        String hash = hash(request);
        UUID id = request.settlementRequestId();

        // No external side effect at all. A later status check returns NOT_FOUND.
        if (request.mode() == SettlementMode.TIMEOUT_BEFORE_PROCESSING) {
            return repository.find(id)
                    .map(existing -> new Submission(verifyHash(existing, hash).response(), false, false))
                    .orElseGet(() -> new Submission(null, false, true));
        }

        SettlementStatus status = switch (request.mode()) {
            case SUCCESS, TIMEOUT_AFTER_PROCESSING -> SettlementStatus.SETTLED;
            case REJECTED -> SettlementStatus.REJECTED;
            case DELAYED_SUCCESS -> SettlementStatus.PENDING;
            case TIMEOUT_BEFORE_PROCESSING -> throw new IllegalStateException("Handled above");
        };
        String reference = status == SettlementStatus.SETTLED ? "SIM-" + id : null;
        Instant due = status == SettlementStatus.PENDING
                ? Instant.now().plusMillis(settlementDelayMs) : null;

        boolean inserted = repository.insertIfAbsent(id, hash, request, status, reference, due);
        SettlementRecord result = verifyHash(repository.find(id).orElseThrow(), hash);

        // Only the first TIMEOUT_AFTER_PROCESSING request loses its response.
        // Duplicate submissions return the persisted result for safe recovery.
        boolean loseResponse = inserted && request.mode() == SettlementMode.TIMEOUT_AFTER_PROCESSING;
        return new Submission(result.response(), loseResponse, false);
    }

    @Transactional
    public SettlementResponse get(UUID id) {
        repository.finalizeDueSettlement(id);
        return repository.find(id)
                .orElseThrow(SettlementNotFoundException::new)
                .response();
    }

    private static SettlementRecord verifyHash(SettlementRecord found, String expectedHash) {
        if (!MessageDigest.isEqual(
                found.requestHash().getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new SettlementConflictException();
        }
        return found;
    }

    private static String hash(SubmitSettlementRequest request) {
        // Canonical byte sequence: all fields that describe the operation.
        String canonical = request.amountMinor() + "\n" +
                request.destinationReference() + "\n" + request.mode().name();
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    record Submission(SettlementResponse response, boolean loseResponse, boolean noProcessing) { }
}
