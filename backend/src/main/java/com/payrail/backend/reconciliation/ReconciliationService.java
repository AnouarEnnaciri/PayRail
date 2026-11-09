package com.payrail.backend.reconciliation;

import com.payrail.backend.settlement.SettlementTransport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Detect-only reconciliation: a consistent local snapshot, external calls OUTSIDE transactions,
 *  then one short transaction to persist the report. No money or transfer state is changed. */
@Service
public class ReconciliationService {
    public record RunView(UUID id, Instant startedAt, Instant completedAt, String status,
                          String triggeredBy, int checksPerformed, int externalChecked, int issuesFound) { }
    public record IssueView(UUID id, UUID runId, String issueType, String resourceType,
                            UUID resourceId, String expectedState, String observedState,
                            String detail, String status, Instant detectedAt, Instant resolvedAt,
                            String resolutionAction, String resolutionActor, String evidenceReference) { }

    private record Draft(String type, String resourceType, UUID resourceId,
                         String expected, String observed, String detail) { }
    private record External(UUID transferId, UUID requestId, long amountMinor,
                            String destination, String status,
                            int reservationCount, int settlementCount, int compensationCount) { }
    private record Snapshot(List<Draft> localIssues, List<External> external,
                            int checksPerformed, boolean truncated) { }

    private final JdbcTemplate jdbc;
    private final SettlementTransport transport;
    private final PlatformTransactionManager transactionManager;
    private final int externalLimit;

    public ReconciliationService(JdbcTemplate jdbc, SettlementTransport transport,
                                 PlatformTransactionManager transactionManager,
                                 @Value("${payrail.reconciliation.max-external-checks:200}") int externalLimit) {
        if (externalLimit < 1 || externalLimit > 10000) {
            throw new IllegalArgumentException("Invalid reconciliation max-external-checks");
        }
        this.jdbc = jdbc;
        this.transport = transport;
        this.transactionManager = transactionManager;
        this.externalLimit = externalLimit;
    }

    public RunView run(String actor) {
        if (actor == null || actor.isBlank() || actor.length() > 128) {
            throw new IllegalArgumentException("Authenticated OPS actor is required");
        }
        UUID runId = UUID.randomUUID();
        Instant start = Instant.now();

        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        readOnly.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        Snapshot snapshot = Objects.requireNonNull(readOnly.execute(ignored -> localSnapshot()));

        List<Draft> issues = new ArrayList<>(snapshot.localIssues());
        int checked = 0;
        // All HTTP I/O occurs after the snapshot transaction has COMMITTED.
        for (External transfer : snapshot.external()) {
            checked++;
            compareExternal(transfer, issues);
        }
        if (snapshot.truncated()) {
            issues.add(new Draft("SCAN_LIMIT_EXCEEDED", "RECONCILIATION_RUN", runId,
                    "All external transfers checked", "Only first " + externalLimit + " checked",
                    "Run PARTIAL: increase max-external-checks or split the scan before relying on its coverage"));
        }
        Instant completed = Instant.now();
        RunView result = new RunView(runId, start, completed,
                snapshot.truncated() ? "PARTIAL" : "COMPLETED", actor,
                Math.addExact(snapshot.checksPerformed(), checked), checked, issues.size());

        TransactionTemplate write = new TransactionTemplate(transactionManager);
        write.executeWithoutResult(ignored -> {
            jdbc.update("""
                    INSERT INTO reconciliation_runs
                      (id, started_at, completed_at, status, triggered_by,
                       checks_performed, external_checked, issues_found)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, result.id(), Timestamp.from(result.startedAt()), Timestamp.from(result.completedAt()), result.status(),
                    result.triggeredBy(), result.checksPerformed(), result.externalChecked(), result.issuesFound());
            for (Draft issue : issues) {
                jdbc.update("""
                        INSERT INTO reconciliation_issues
                          (id, run_id, issue_type, resource_type, resource_id,
                           expected_state, observed_state, detail, detected_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), runId, issue.type(), issue.resourceType(),
                        issue.resourceId(), issue.expected(), issue.observed(), issue.detail(), Timestamp.from(completed));
            }
        });
        return result;
    }

    private Snapshot localSnapshot() {
        List<Draft> problems = new ArrayList<>();
        int customerCount = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM accounts WHERE account_type = 'CUSTOMER'", Integer.class));
        int ledgerCount = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transactions", Integer.class));
        int externalCount = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM transfers WHERE transfer_type = 'EXTERNAL'", Integer.class));

        problems.addAll(jdbc.query("""
                SELECT a.id, a.balance_minor, COALESCE(SUM(e.signed_amount_minor), 0) AS ledger_balance
                FROM accounts a LEFT JOIN ledger_entries e ON e.account_id = a.id
                WHERE a.account_type = 'CUSTOMER'
                GROUP BY a.id, a.balance_minor
                HAVING a.balance_minor <> COALESCE(SUM(e.signed_amount_minor), 0)
                """, (rs, row) -> new Draft("BALANCE_LEDGER_MISMATCH", "ACCOUNT",
                rs.getObject("id", UUID.class),
                "materialized=" + rs.getLong("balance_minor"),
                "ledger=" + rs.getLong("ledger_balance"),
                "Customer balance differs from the append-only ledger")));

        problems.addAll(jdbc.query("""
                SELECT t.id, COALESCE(SUM(e.signed_amount_minor),0) AS total,
                       COUNT(e.id) AS entry_count
                FROM ledger_transactions t LEFT JOIN ledger_entries e ON e.ledger_transaction_id = t.id
                GROUP BY t.id
                HAVING COALESCE(SUM(e.signed_amount_minor),0) <> 0 OR COUNT(e.id) < 2
                """, (rs, row) -> new Draft("LEDGER_IMBALANCE", "LEDGER_TRANSACTION",
                rs.getObject("id", UUID.class), "sum=0; entries>=2",
                "sum=" + rs.getLong("total") + "; entries=" + rs.getLong("entry_count"),
                "Ledger transaction is unbalanced or incomplete")));

        List<UUID> clearing = jdbc.query("""
                SELECT id FROM accounts WHERE reference = 'SETTLEMENT_CLEARING' AND account_type = 'SYSTEM'
                """, (rs, row) -> rs.getObject("id", UUID.class));
        if (clearing.size() != 1) throw new IllegalStateException("SETTLEMENT_CLEARING is missing or invalid");
        UUID clearingId = clearing.get(0);
        long actualClearing = Objects.requireNonNull(jdbc.queryForObject("""
                SELECT COALESCE(SUM(signed_amount_minor),0) FROM ledger_entries WHERE account_id = ?
                """, Long.class, clearingId));
        long expectedClearing = Objects.requireNonNull(jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount_minor),0) FROM transfers
                WHERE transfer_type = 'EXTERNAL'
                  AND status IN ('PENDING_SETTLEMENT', 'RECONCILIATION_REQUIRED')
                """, Long.class));
        if (actualClearing != expectedClearing) {
            problems.add(new Draft("CLEARING_BALANCE_MISMATCH", "SYSTEM_ACCOUNT", clearingId,
                    "unresolved=" + expectedClearing, "ledger=" + actualClearing,
                    "SETTLEMENT_CLEARING balance differs from outstanding external reservations"));
        }

        List<External> selected = jdbc.query("""
                SELECT t.id, t.settlement_request_id, t.amount_minor, t.destination_reference, t.status,
                   (SELECT COUNT(*) FROM ledger_transactions l
                     WHERE l.transfer_id=t.id AND l.effect_type='EXTERNAL_RESERVATION') AS reserve_count,
                   (SELECT COUNT(*) FROM ledger_transactions l
                     WHERE l.transfer_id=t.id AND l.effect_type='EXTERNAL_SETTLEMENT_FINALIZATION') AS settle_count,
                   (SELECT COUNT(*) FROM ledger_transactions l
                     WHERE l.transfer_id=t.id AND l.effect_type='EXTERNAL_COMPENSATION') AS compensate_count
                FROM transfers t WHERE t.transfer_type='EXTERNAL'
                ORDER BY t.created_at, t.id LIMIT ?
                """, (rs, row) -> new External(rs.getObject("id", UUID.class),
                rs.getObject("settlement_request_id", UUID.class), rs.getLong("amount_minor"),
                rs.getString("destination_reference"), rs.getString("status"),
                rs.getInt("reserve_count"), rs.getInt("settle_count"),
                rs.getInt("compensate_count")), externalLimit);
        for (External transfer : selected) {
            boolean expectedEffects = transfer.reservationCount() == 1 && switch (transfer.status()) {
                case "SETTLED" -> transfer.settlementCount() == 1 && transfer.compensationCount() == 0;
                case "FAILED" -> transfer.settlementCount() == 0 && transfer.compensationCount() == 1;
                case "PENDING_SETTLEMENT", "RECONCILIATION_REQUIRED" ->
                        transfer.settlementCount() == 0 && transfer.compensationCount() == 0;
                default -> false;
            };
            if (!expectedEffects) {
                problems.add(new Draft("MISSING_FINANCIAL_EFFECT", "TRANSFER", transfer.transferId(),
                        "reservation=1; terminal effect matches " + transfer.status(),
                        "reservation=" + transfer.reservationCount()
                                + "; settlement=" + transfer.settlementCount()
                                + "; compensation=" + transfer.compensationCount(),
                        "Financial effects disagree with internal transfer state"));
            }
        }
        return new Snapshot(problems, selected,
                Math.addExact(Math.addExact(Math.addExact(customerCount, ledgerCount), 1), selected.size()),
                externalCount > externalLimit);
    }

    private void compareExternal(External t, List<Draft> issues) {
        SettlementTransport.Receipt receipt;
        try {
            receipt = transport.get(t.requestId());
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                if ("SETTLED".equals(t.status())) {
                    issues.add(new Draft("INTERNAL_SETTLED_EXTERNAL_MISSING", "TRANSFER", t.transferId(),
                            "external SETTLED", "external NOT_FOUND",
                            "Missing external record is not proof of rejection; investigate before any action"));
                } else if ("RECONCILIATION_REQUIRED".equals(t.status()) || "FAILED".equals(t.status())) {
                    issues.add(new Draft("UNKNOWN_SETTLEMENT_STATE", "TRANSFER", t.transferId(),
                            "verified external outcome", "external NOT_FOUND",
                            "NOT_FOUND does not establish a definitive financial outcome"));
                }
                return;
            }
            unknown(t, issues, "HTTP " + ex.getStatusCode().value());
            return;
        } catch (RestClientException ex) {
            unknown(t, issues, ex.getClass().getSimpleName());
            return;
        }
        if (receipt == null) {
            unknown(t, issues, "null receipt");
            return;
        }
        if (!Objects.equals(receipt.settlementRequestId(), t.requestId())
                || receipt.amountMinor() != t.amountMinor()
                || !Objects.equals(receipt.destinationReference(), t.destination())) {
            issues.add(new Draft("SETTLEMENT_AMOUNT_MISMATCH", "TRANSFER", t.transferId(),
                    "matching request, amount and destination", "external receipt differs",
                    "External evidence does not match the original transfer; no repair permitted"));
            return;
        }
        String externalState = receipt.status();
        if (externalState == null || !List.of("SETTLED", "REJECTED", "PENDING").contains(externalState)
                || ("SETTLED".equals(externalState)
                    && (receipt.externalReference() == null || receipt.externalReference().isBlank()))) {
            unknown(t, issues, "invalid external receipt state");
            return;
        }
        if (List.of("PENDING_SETTLEMENT", "RECONCILIATION_REQUIRED").contains(t.status())) {
            if ("SETTLED".equals(externalState)) {
                issues.add(new Draft("EXTERNAL_SETTLED_INTERNAL_PENDING", "TRANSFER", t.transferId(),
                        "internal SETTLED", "internal " + t.status() + "; external SETTLED",
                        "Verified external settlement has not been finalized internally"));
            } else if ("REJECTED".equals(externalState)) {
                issues.add(new Draft("EXTERNAL_REJECTED_INTERNAL_PENDING", "TRANSFER", t.transferId(),
                        "internal FAILED", "internal " + t.status() + "; external REJECTED",
                        "Verified external rejection has not been compensated internally"));
            }
        } else if (("SETTLED".equals(t.status()) && !"SETTLED".equals(externalState))
                || ("FAILED".equals(t.status()) && !"REJECTED".equals(externalState))) {
            issues.add(new Draft("EXTERNAL_STATE_CONFLICT", "TRANSFER", t.transferId(),
                    "external evidence consistent with " + t.status(), "external " + externalState,
                    "An already-terminal internal transfer contradicts external evidence; manual investigation only"));
        }
    }

    private void unknown(External t, List<Draft> issues, String observation) {
        issues.add(new Draft("UNKNOWN_SETTLEMENT_STATE", "TRANSFER", t.transferId(),
                "verified external outcome", observation,
                "External result is unavailable or malformed; no financial action is authorized"));
    }

    public RunView getRun(UUID id) {
        List<RunView> found = jdbc.query("""
                SELECT * FROM reconciliation_runs WHERE id = ?
                """, (rs, row) -> new RunView(rs.getObject("id", UUID.class),
                rs.getTimestamp("started_at").toInstant(), rs.getTimestamp("completed_at").toInstant(),
                rs.getString("status"), rs.getString("triggered_by"), rs.getInt("checks_performed"),
                rs.getInt("external_checked"), rs.getInt("issues_found")), id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Reconciliation run not found");
        return found.get(0);
    }

    public List<IssueView> issues(UUID runId) {
        getRun(runId);
        return jdbc.query("""
                SELECT * FROM reconciliation_issues WHERE run_id = ? ORDER BY detected_at, id
                """, (rs, row) -> new IssueView(rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class), rs.getString("issue_type"),
                rs.getString("resource_type"), rs.getObject("resource_id", UUID.class),
                rs.getString("expected_state"), rs.getString("observed_state"),
                rs.getString("detail"), rs.getString("status"),
                rs.getTimestamp("detected_at").toInstant(),
                rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant(),
                rs.getString("resolution_action"), rs.getString("resolution_actor"),
                rs.getString("evidence_reference")), runId);
    }
}
