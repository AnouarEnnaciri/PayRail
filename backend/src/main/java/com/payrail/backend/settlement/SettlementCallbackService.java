package com.payrail.backend.settlement;

import com.payrail.backend.transfer.Transfer;
import com.payrail.backend.transfer.TransferRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One PostgreSQL transaction: event identity, outbox completion and terminal ledger effect. */
@Service
public class SettlementCallbackService {
    public record Result(String disposition, UUID transferId) { }
    private record Outbox(UUID id, UUID transferId, String status) { }
    private record Prior(String fingerprint, UUID transferId) { }

    private final JdbcTemplate jdbc;
    private final TransferRepository transfers;
    private final ExternalSettlementFinalizationService finalizer;

    public SettlementCallbackService(JdbcTemplate jdbc, TransferRepository transfers,
                                     ExternalSettlementFinalizationService finalizer) {
        this.jdbc = jdbc;
        this.transfers = transfers;
        this.finalizer = finalizer;
    }

    @Transactional
    public Result accept(SettlementCallbackEvent event) {
        if (!"SETTLED".equals(event.status()) && !"REJECTED".equals(event.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only terminal callbacks are supported");
        }
        if ("SETTLED".equals(event.status()) &&
                (event.externalReference() == null || event.externalReference().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Settled callback requires an external reference");
        }

        // Lock the outbox FIRST, the same lock order as the worker. The event id is not an authorization.
        List<Outbox> jobs = jdbc.query("""
                SELECT e.id, e.aggregate_id, e.status
                FROM outbox_events e JOIN transfers t ON t.id = e.aggregate_id
                WHERE e.event_type = 'SETTLEMENT_REQUESTED' AND t.settlement_request_id = ?
                FOR UPDATE OF e
                """, (rs, row) -> new Outbox(
                rs.getObject("id", UUID.class), rs.getObject("aggregate_id", UUID.class),
                rs.getString("status")), event.settlementRequestId());
        if (jobs.size() != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Settlement request has no unique outbox event");
        }
        Outbox job = jobs.get(0);
        String fingerprint = SettlementCallbackAuthenticator.fingerprint(event);
        List<Prior> existing = jdbc.query("""
                SELECT payload_fingerprint, transfer_id FROM settlement_callbacks WHERE event_id = ?
                """, (rs, row) -> new Prior(rs.getString("payload_fingerprint"),
                rs.getObject("transfer_id", UUID.class)), event.eventId());
        if (!existing.isEmpty()) {
            Prior prior = existing.get(0);
            if (!prior.fingerprint().equals(fingerprint) || !prior.transferId().equals(job.transferId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Callback event identity reused with different payload");
            }
            return new Result("DUPLICATE", job.transferId());
        }

        Transfer transfer = transfers.findByIdForUpdate(job.transferId())
                .orElseThrow(() -> new IllegalStateException("Callback transfer disappeared"));
        if (!"EXTERNAL".equals(transfer.getTransferType())
                || !Objects.equals(event.settlementRequestId(), transfer.getSettlementRequestId())
                || event.amountMinor() != transfer.getAmountMinor()
                || !Objects.equals(event.destinationReference(), transfer.getDestinationReference())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Callback does not match reserved funds");
        }

        String disposition;
        if ("PROCESSED".equals(job.status())) {
            String expected = "SETTLED".equals(event.status()) ? "SETTLED" : "FAILED";
            if (!expected.equals(transfer.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Callback contradicts finalized transfer");
            }
            disposition = "ALREADY_FINALIZED";
        } else {
            if (!List.of("PENDING", "PROCESSING", "DEAD_LETTER").contains(job.status())) {
                throw new IllegalStateException("Unexpected outbox status: " + job.status());
            }
            // Finalizer rechecks amount, destination, terminal evidence and state under a row lock.
            // The unique M9 ledger index makes conflicting financial terminal effects impossible.
            finalizer.apply(job.transferId(), event.settlementRequestId(), event.amountMinor(),
                    event.destinationReference(), event.status(), event.externalReference());
            int ack = jdbc.update("""
                    UPDATE outbox_events
                    SET status = 'PROCESSED', processed_at = now(),
                        locked_until = NULL, locked_by = NULL, last_error = NULL
                    WHERE id = ?
                    """, job.id());
            if (ack != 1) throw new IllegalStateException("Callback outbox acknowledgment failed");
            disposition = "FINALIZED";
        }
        jdbc.update("""
                INSERT INTO settlement_callbacks
                    (event_id, transfer_id, settlement_request_id, payload_fingerprint,
                     external_status, disposition)
                VALUES (?, ?, ?, ?, ?, ?)
                """, event.eventId(), job.transferId(), event.settlementRequestId(),
                fingerprint, event.status(), disposition);
        return new Result(disposition, job.transferId());
    }
}
