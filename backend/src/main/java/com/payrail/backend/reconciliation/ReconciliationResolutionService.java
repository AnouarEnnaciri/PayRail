package com.payrail.backend.reconciliation;

import com.payrail.backend.settlement.ExternalSettlementFinalizationService;
import com.payrail.backend.settlement.SettlementTransport;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Explicit OPS-only recovery: status lookup is BEFORE the DB transaction.
 *  A 404, timeout or inconsistent receipt can NEVER authorize a refund or settlement. */
@Service
public class ReconciliationResolutionService {
    public record Resolution(UUID issueId, UUID transferId, String disposition, String transferState) { }
    private record Target(UUID issueId, UUID transferId, String issueType, String issueStatus,
                          UUID settlementRequestId, long amountMinor, String destination) { }
    private record Job(UUID id, String status) { }

    private final JdbcTemplate jdbc;
    private final SettlementTransport transport;
    private final ExternalSettlementFinalizationService finalizer;
    private final TransactionTemplate tx;

    public ReconciliationResolutionService(JdbcTemplate jdbc, SettlementTransport transport,
                                           ExternalSettlementFinalizationService finalizer,
                                           PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transport = transport;
        this.finalizer = finalizer;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public Resolution resolve(UUID issueId, String actor) {
        if (actor == null || actor.isBlank() || actor.length() > 128) {
            throw new IllegalArgumentException("Authenticated OPS actor required");
        }
        List<Target> candidates = jdbc.query("""
                SELECT i.id, i.resource_id, i.issue_type, i.status,
                       t.settlement_request_id, t.amount_minor, t.destination_reference
                FROM reconciliation_issues i
                JOIN transfers t ON t.id=i.resource_id AND t.transfer_type='EXTERNAL'
                WHERE i.id=? AND i.resource_type='TRANSFER'
                """, (rs, row) -> new Target(rs.getObject("id", UUID.class),
                rs.getObject("resource_id", UUID.class), rs.getString("issue_type"),
                rs.getString("status"), rs.getObject("settlement_request_id", UUID.class),
                rs.getLong("amount_minor"), rs.getString("destination_reference")), issueId);
        if (candidates.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resolvable issue not found");
        Target target = candidates.get(0);
        if (!List.of("EXTERNAL_SETTLED_INTERNAL_PENDING", "EXTERNAL_REJECTED_INTERNAL_PENDING")
                .contains(target.issueType())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This issue needs investigation, not financial repair");
        }
        if ("RESOLVED".equals(target.issueStatus())) {
            return new Resolution(issueId, target.transferId(), "ALREADY_RESOLVED", transferState(target.transferId()));
        }

        SettlementTransport.Receipt evidence;
        try {
            evidence = transport.get(target.settlementRequestId());
        } catch (RestClientException ex) {
            // NOT_FOUND means uncertain; no automatic rejection, even for a dead-letter job.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Verified external terminal evidence unavailable; no financial action", ex);
        }
        if (evidence == null
                || !Objects.equals(evidence.settlementRequestId(), target.settlementRequestId())
                || evidence.amountMinor() != target.amountMinor()
                || !Objects.equals(evidence.destinationReference(), target.destination())
                || !("SETTLED".equals(evidence.status()) || "REJECTED".equals(evidence.status()))
                || ("SETTLED".equals(evidence.status())
                   && (evidence.externalReference() == null || evidence.externalReference().isBlank()
                       || evidence.externalReference().length() > 128))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "External receipt is incomplete or inconsistent");
        }
        String required = target.issueType().equals("EXTERNAL_SETTLED_INTERNAL_PENDING") ? "SETTLED" : "REJECTED";
        if (!required.equals(evidence.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "External truth has changed since detection; run reconciliation again");
        }

        // A callback or worker may have finalized while the GET was in flight.
        // Re-check EVERYTHING after acquiring the outbox -> issue -> transfer locks.
        return Objects.requireNonNull(tx.execute(ignored -> applyVerified(target, evidence, actor)));
    }

    private Resolution applyVerified(Target initial, SettlementTransport.Receipt evidence, String actor) {
        List<Job> jobs = jdbc.query("""
                SELECT id, status FROM outbox_events
                WHERE aggregate_id=? AND event_type='SETTLEMENT_REQUESTED'
                FOR UPDATE
                """, (rs, row) -> new Job(rs.getObject("id", UUID.class),
                rs.getString("status")), initial.transferId());
        if (jobs.size() != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Unique outbox job missing");
        Job job = jobs.get(0);

        List<Target> issues = jdbc.query("""
                SELECT i.id, i.resource_id, i.issue_type, i.status,
                       t.settlement_request_id, t.amount_minor, t.destination_reference
                FROM reconciliation_issues i JOIN transfers t ON t.id=i.resource_id
                WHERE i.id=? FOR UPDATE OF i
                """, (rs, row) -> new Target(rs.getObject("id", UUID.class),
                rs.getObject("resource_id", UUID.class), rs.getString("issue_type"),
                rs.getString("status"), rs.getObject("settlement_request_id", UUID.class),
                rs.getLong("amount_minor"), rs.getString("destination_reference")), initial.issueId());
        if (issues.size() != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Issue changed during resolution");
        Target current = issues.get(0);
        if ("RESOLVED".equals(current.issueStatus())) {
            return new Resolution(current.issueId(), current.transferId(), "ALREADY_RESOLVED", transferState(current.transferId()));
        }
        if (!current.issueType().equals(initial.issueType())
                || !current.transferId().equals(initial.transferId())
                || !Objects.equals(current.settlementRequestId(), evidence.settlementRequestId())
                || current.amountMinor() != evidence.amountMinor()
                || !Objects.equals(current.destination(), evidence.destinationReference())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Issue or transfer changed during resolution");
        }

        List<String> states = jdbc.query("""
                SELECT status FROM transfers WHERE id=? FOR UPDATE
                """, (rs, row) -> rs.getString("status"), current.transferId());
        if (states.size() != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Transfer missing");
        String state = states.get(0);
        String finalState = "SETTLED".equals(evidence.status()) ? "SETTLED" : "FAILED";
        String disposition;
        if ("PENDING_SETTLEMENT".equals(state) || "RECONCILIATION_REQUIRED".equals(state)) {
            // Ledger post, customer credit (if rejected), transfer change and issue audit commit together.
            finalizer.apply(current.transferId(), evidence.settlementRequestId(), evidence.amountMinor(),
                    evidence.destinationReference(), evidence.status(), evidence.externalReference());
            int updated = jdbc.update("""
                    UPDATE outbox_events SET status='PROCESSED', processed_at=now(),
                        locked_until=NULL, locked_by=NULL, last_error=NULL
                    WHERE id=?
                    """, job.id());
            if (updated != 1) throw new IllegalStateException("Cannot acknowledge finalized outbox event");
            disposition = "SETTLED".equals(evidence.status())
                    ? "FINALIZED_VERIFIED_SETTLEMENT" : "COMPENSATED_VERIFIED_REJECTION";
        } else if (finalState.equals(state)) {
            int expected = countEffect(current.transferId(),
                    "SETTLED".equals(state) ? "EXTERNAL_SETTLEMENT_FINALIZATION" : "EXTERNAL_COMPENSATION");
            int conflicting = countEffect(current.transferId(),
                    "SETTLED".equals(state) ? "EXTERNAL_COMPENSATION" : "EXTERNAL_SETTLEMENT_FINALIZATION");
            if (expected != 1 || conflicting != 0 || !"PROCESSED".equals(job.status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Terminal transfer effects need investigation");
            }
            disposition = "ALREADY_FINALIZED";
        } else {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Internal terminal state contradicts verified external evidence");
        }
        int updated = jdbc.update("""
                UPDATE reconciliation_issues SET status='RESOLVED', resolved_at=now(),
                    resolution_action=?, resolution_actor=?, evidence_reference=?
                WHERE id=? AND status='OPEN'
                """, disposition, actor,
                evidence.status() + ":" + evidence.settlementRequestId()
                        + (evidence.externalReference() == null ? "" : ":" + evidence.externalReference()),
                current.issueId());
        if (updated != 1) throw new IllegalStateException("Cannot audit issue resolution");
        return new Resolution(current.issueId(), current.transferId(), disposition, finalState);
    }

    private int countEffect(UUID transferId, String effect) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT count(*) FROM ledger_transactions WHERE transfer_id=? AND effect_type=?
                """, Integer.class, transferId, effect));
    }

    private String transferState(UUID transferId) {
        List<String> states = jdbc.query("SELECT status FROM transfers WHERE id=?",
                (rs, row) -> rs.getString("status"), transferId);
        return states.isEmpty() ? "UNKNOWN" : states.get(0);
    }
}
