package com.payrail.backend.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Each public method is one short PostgreSQL transaction. No HTTP calls here. */
@Service
public class OutboxStore {

    public record Claim(UUID eventId, UUID transferId, UUID settlementRequestId,
                        long amountMinor, String destinationReference, String mode,
                        int attemptNumber, UUID attemptId, String leaseOwner) { }

    public record Completion(String outcome, Integer httpStatus, String errorCategory,
                             String externalReference, String errorDetail,
                             boolean terminalReceipt, boolean permanentError,
                             long latencyMs) { }

    private final JdbcTemplate jdbc;
    private final int leaseSeconds;
    private final int maxAttempts;
    private final long initialBackoffSeconds;
    private final long maxBackoffSeconds;

    public OutboxStore(JdbcTemplate jdbc,
                       @Value("${payrail.outbox.worker.lease-seconds:30}") int leaseSeconds,
                       @Value("${payrail.outbox.worker.max-attempts:8}") int maxAttempts,
                       @Value("${payrail.outbox.worker.base-backoff-seconds:2}") long initialBackoffSeconds,
                       @Value("${payrail.outbox.worker.max-backoff-seconds:60}") long maxBackoffSeconds) {
        if (leaseSeconds < 1 || maxAttempts < 1 || initialBackoffSeconds < 1
                || maxBackoffSeconds < initialBackoffSeconds) {
            throw new IllegalArgumentException("Invalid outbox worker configuration");
        }
        this.jdbc = jdbc;
        this.leaseSeconds = leaseSeconds;
        this.maxAttempts = maxAttempts;
        this.initialBackoffSeconds = initialBackoffSeconds;
        this.maxBackoffSeconds = maxBackoffSeconds;
    }

    /** Atomic claim, with SKIP LOCKED for concurrent workers and lease recovery after process death. */
    @Transactional
    public Claim claimNext() {
        // A crashed final attempt must not silently escape the retry budget.
        jdbc.update("""
                UPDATE outbox_events
                SET status = 'DEAD_LETTER', locked_until = NULL, locked_by = NULL,
                    last_error = 'Lease expired after retry budget'
                WHERE status = 'PROCESSING' AND locked_until <= now()
                  AND attempt_count >= ?
                """, maxAttempts);
        String owner = UUID.randomUUID().toString(); // unique fencing token for one claim
        List<Claim> results = jdbc.query("""
                WITH candidate AS (
                    SELECT id FROM outbox_events
                    WHERE event_type = 'SETTLEMENT_REQUESTED'
                      AND attempt_count < ?
                      AND ((status = 'PENDING' AND available_at <= now())
                         OR (status = 'PROCESSING' AND locked_until <= now()))
                    ORDER BY available_at, created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE outbox_events e
                SET status = 'PROCESSING', locked_by = ?,
                    locked_until = now() + (? * INTERVAL '1 second'),
                    attempt_count = e.attempt_count + 1
                FROM candidate
                WHERE e.id = candidate.id
                RETURNING e.id, e.aggregate_id, e.attempt_count,
                          (e.payload_json ->> 'settlementRequestId')::uuid AS settlement_request_id,
                          (e.payload_json ->> 'amountMinor')::bigint AS amount_minor,
                          e.payload_json ->> 'destinationReference' AS destination_reference,
                          e.payload_json ->> 'mode' AS settlement_mode
                """, (rs, index) -> new Claim(
                        rs.getObject("id", UUID.class),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getObject("settlement_request_id", UUID.class),
                        rs.getLong("amount_minor"),
                        rs.getString("destination_reference"),
                        rs.getString("settlement_mode"),
                        rs.getInt("attempt_count"),
                        UUID.randomUUID(), owner
                ), maxAttempts, owner, leaseSeconds);

        if (results.isEmpty()) return null;
        Claim claim = results.get(0);
        jdbc.update("""
                INSERT INTO settlement_attempts
                    (id, outbox_event_id, transfer_id, settlement_request_id,
                     attempt_number, started_at, outcome)
                VALUES (?, ?, ?, ?, ?, now(), 'IN_FLIGHT')
                """, claim.attemptId(), claim.eventId(), claim.transferId(),
                claim.settlementRequestId(), claim.attemptNumber());
        return claim;
    }

    /** Durable receipt + outbox transition are committed together. Fencing prevents stale workers overwriting new claims. */
    @Transactional
    public void complete(Claim claim, Completion completion) {
        int recorded = jdbc.update("""
                UPDATE settlement_attempts
                SET completed_at = now(), outcome = ?, http_status = ?, error_category = ?,
                    external_reference = ?, last_error = ?, latency_ms = ?
                WHERE id = ? AND outcome = 'IN_FLIGHT'
                """, completion.outcome(), completion.httpStatus(), completion.errorCategory(),
                completion.externalReference(), abbreviate(completion.errorDetail()),
                completion.latencyMs(), claim.attemptId());
        if (recorded != 1) {
            throw new IllegalStateException("Attempt has already been completed or is missing");
        }

        if (completion.terminalReceipt()) {
            jdbc.update("""
                    UPDATE outbox_events
                    SET status = 'PROCESSED', processed_at = now(),
                        locked_until = NULL, locked_by = NULL, last_error = NULL
                    WHERE id = ? AND status = 'PROCESSING'
                      AND locked_by = ? AND attempt_count = ?
                    """, claim.eventId(), claim.leaseOwner(), claim.attemptNumber());
            return;
        }

        boolean exhausted = claim.attemptNumber() >= maxAttempts;
        if (completion.permanentError() || exhausted) {
            jdbc.update("""
                    UPDATE outbox_events
                    SET status = 'DEAD_LETTER', locked_until = NULL,
                        locked_by = NULL, last_error = ?
                    WHERE id = ? AND status = 'PROCESSING'
                      AND locked_by = ? AND attempt_count = ?
                    """, abbreviate(completion.errorDetail()),
                    claim.eventId(), claim.leaseOwner(), claim.attemptNumber());
        } else {
            jdbc.update("""
                    UPDATE outbox_events
                    SET status = 'PENDING', locked_until = NULL, locked_by = NULL,
                        available_at = now() + (? * INTERVAL '1 second'), last_error = ?
                    WHERE id = ? AND status = 'PROCESSING'
                      AND locked_by = ? AND attempt_count = ?
                    """, backoffSeconds(claim.attemptNumber()),
                    abbreviate(completion.errorDetail()),
                    claim.eventId(), claim.leaseOwner(), claim.attemptNumber());
        }
    }

    private long backoffSeconds(int attemptNumber) {
        long delay = initialBackoffSeconds;
        for (int i = 1; i < attemptNumber && delay < maxBackoffSeconds; i++) {
            delay = delay > maxBackoffSeconds / 2 ? maxBackoffSeconds : delay * 2;
        }
        return Math.min(delay, maxBackoffSeconds);
    }

    private static String abbreviate(String value) {
        if (value == null) return null;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
