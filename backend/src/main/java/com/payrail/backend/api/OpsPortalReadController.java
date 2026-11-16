package com.payrail.backend.api;

import com.payrail.backend.reconciliation.ReconciliationService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Read-only OPS projections; SecurityConfig already requires ROLE_OPS for /api/ops/**. */
@RestController
@Profile("demo")
@RequestMapping("/api/ops/portal")
public class OpsPortalReadController {
    public record Overview(long transfers, long internalCompleted, long externalSettled,
                           long externalPending, long requiresReconciliation,
                           long failed, long outboxPending, long outboxProcessing,
                           long deadLetters, long openIssues, long ledgerTransactions,
                           long clearingMinor) { }

    public record OutboxView(UUID id, UUID transferId, String status, int attemptCount,
                             Instant availableAt, Instant createdAt, Instant processedAt,
                             Instant lockedUntil, boolean hasError) { }
    public record AttemptView(UUID id, UUID transferId, UUID settlementRequestId, int attemptNumber,
                              String outcome, Integer httpStatus, String errorCategory,
                              Long latencyMs, Instant startedAt, Instant completedAt) { }
    public record CallbackView(UUID eventId, UUID transferId, UUID settlementRequestId,
                               String externalStatus, String disposition, Instant receivedAt) { }
    public record TimelineEvent(UUID id, String type, String title, String detail, Instant at) { }

    private final JdbcTemplate jdbc;
    public OpsPortalReadController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/overview")
    public Overview overview() {
        return jdbc.queryForObject("""
                SELECT
                  (SELECT COUNT(*) FROM transfers) AS transfers,
                  (SELECT COUNT(*) FROM transfers WHERE transfer_type='INTERNAL' AND status='COMPLETED') AS internal_completed,
                  (SELECT COUNT(*) FROM transfers WHERE transfer_type='EXTERNAL' AND status='SETTLED') AS external_settled,
                  (SELECT COUNT(*) FROM transfers WHERE transfer_type='EXTERNAL' AND status='PENDING_SETTLEMENT') AS external_pending,
                  (SELECT COUNT(*) FROM transfers WHERE status='RECONCILIATION_REQUIRED') AS requires_reconciliation,
                  (SELECT COUNT(*) FROM transfers WHERE status='FAILED') AS failed,
                  (SELECT COUNT(*) FROM outbox_events WHERE status='PENDING') AS outbox_pending,
                  (SELECT COUNT(*) FROM outbox_events WHERE status='PROCESSING') AS outbox_processing,
                  (SELECT COUNT(*) FROM outbox_events WHERE status='DEAD_LETTER') AS dead_letters,
                  (SELECT COUNT(*) FROM reconciliation_issues WHERE status='OPEN') AS open_issues,
                  (SELECT COUNT(*) FROM ledger_transactions) AS ledger_transactions,
                  (SELECT COALESCE(SUM(e.signed_amount_minor),0)
                   FROM ledger_entries e JOIN accounts a ON a.id=e.account_id
                   WHERE a.reference='SETTLEMENT_CLEARING') AS clearing_minor
                """, (rs, row) -> new Overview(
                rs.getLong("transfers"), rs.getLong("internal_completed"), rs.getLong("external_settled"),
                rs.getLong("external_pending"), rs.getLong("requires_reconciliation"),
                rs.getLong("failed"), rs.getLong("outbox_pending"), rs.getLong("outbox_processing"),
                rs.getLong("dead_letters"), rs.getLong("open_issues"), rs.getLong("ledger_transactions"),
                rs.getLong("clearing_minor")));
    }

    @GetMapping("/transfers")
    public List<ClientPortalReadController.TransferView> transfers(@RequestParam(defaultValue = "100") int limit) {
        return jdbc.query(ClientPortalReadController.transferSelect() + """
                ORDER BY t.created_at DESC, t.id DESC LIMIT ?
                """, ClientPortalReadController::transferRow, ClientPortalReadController.bounded(limit));
    }

    @GetMapping("/transfers/{id}")
    public ClientPortalReadController.TransferView transfer(@PathVariable UUID id) {
        List<ClientPortalReadController.TransferView> transfers = jdbc.query(
                ClientPortalReadController.transferSelect() + "WHERE t.id=?",
                ClientPortalReadController::transferRow, id);
        if (transfers.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found");
        return transfers.get(0);
    }

    @GetMapping("/outbox")
    public List<OutboxView> outbox(@RequestParam(defaultValue = "100") int limit) {
        return jdbc.query("""
                SELECT id, aggregate_id, status, attempt_count, available_at, created_at,
                       processed_at, locked_until, (last_error IS NOT NULL) AS has_error
                FROM outbox_events ORDER BY created_at DESC, id DESC LIMIT ?
                """, (rs, row) -> new OutboxView(rs.getObject("id", UUID.class),
                rs.getObject("aggregate_id", UUID.class), rs.getString("status"),
                rs.getInt("attempt_count"), ts(rs, "available_at"), ts(rs, "created_at"),
                ts(rs, "processed_at"), ts(rs, "locked_until"), rs.getBoolean("has_error")),
                ClientPortalReadController.bounded(limit));
    }

    @GetMapping("/attempts")
    public List<AttemptView> attempts(@RequestParam(defaultValue = "100") int limit) {
        return jdbc.query("""
                SELECT id, transfer_id, settlement_request_id, attempt_number, outcome,
                       http_status, error_category, latency_ms, started_at, completed_at
                FROM settlement_attempts ORDER BY started_at DESC, id DESC LIMIT ?
                """, (rs, row) -> new AttemptView(rs.getObject("id", UUID.class),
                rs.getObject("transfer_id", UUID.class), rs.getObject("settlement_request_id", UUID.class),
                rs.getInt("attempt_number"), rs.getString("outcome"), nullableInt(rs, "http_status"),
                rs.getString("error_category"), nullableLong(rs, "latency_ms"),
                ts(rs, "started_at"), ts(rs, "completed_at")),
                ClientPortalReadController.bounded(limit));
    }

    @GetMapping("/callbacks")
    public List<CallbackView> callbacks(@RequestParam(defaultValue = "100") int limit) {
        return jdbc.query("""
                SELECT event_id, transfer_id, settlement_request_id, external_status,
                       disposition, received_at
                FROM settlement_callbacks ORDER BY received_at DESC, event_id DESC LIMIT ?
                """, (rs, row) -> new CallbackView(rs.getObject("event_id", UUID.class),
                rs.getObject("transfer_id", UUID.class), rs.getObject("settlement_request_id", UUID.class),
                rs.getString("external_status"), rs.getString("disposition"), ts(rs, "received_at")),
                ClientPortalReadController.bounded(limit));
    }

    @GetMapping("/reconciliation/runs")
    public List<ReconciliationService.RunView> runs(@RequestParam(defaultValue = "30") int limit) {
        return jdbc.query("""
                SELECT * FROM reconciliation_runs ORDER BY started_at DESC, id DESC LIMIT ?
                """, (rs, row) -> new ReconciliationService.RunView(rs.getObject("id", UUID.class),
                ts(rs, "started_at"), ts(rs, "completed_at"), rs.getString("status"),
                rs.getString("triggered_by"), rs.getInt("checks_performed"),
                rs.getInt("external_checked"), rs.getInt("issues_found")),
                ClientPortalReadController.bounded(limit));
    }

    @GetMapping("/reconciliation/issues")
    public List<ReconciliationService.IssueView> issues(@RequestParam(defaultValue = "100") int limit) {
        return jdbc.query("""
                SELECT * FROM reconciliation_issues
                ORDER BY CASE WHEN status='OPEN' THEN 0 ELSE 1 END, detected_at DESC, id DESC LIMIT ?
                """, (rs, row) -> new ReconciliationService.IssueView(rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class), rs.getString("issue_type"),
                rs.getString("resource_type"), rs.getObject("resource_id", UUID.class),
                rs.getString("expected_state"), rs.getString("observed_state"),
                rs.getString("detail"), rs.getString("status"), ts(rs, "detected_at"),
                ts(rs, "resolved_at"), rs.getString("resolution_action"),
                rs.getString("resolution_actor"), rs.getString("evidence_reference")),
                ClientPortalReadController.bounded(limit));
    }

    @GetMapping("/timeline/{transferId}")
    public List<TimelineEvent> timeline(@PathVariable UUID transferId) {
        transfer(transferId); // 404 if invalid; never infer events for a missing transfer
        List<TimelineEvent> events = new ArrayList<>();
        events.addAll(jdbc.query("""
                SELECT id, created_at FROM transfers WHERE id=?
                """, (rs, row) -> new TimelineEvent(rs.getObject("id", UUID.class), "TRANSFER",
                "Transfer recorded", "Durable transfer record created", ts(rs, "created_at")), transferId));
        events.addAll(jdbc.query("""
                SELECT created_at FROM transfer_idempotency WHERE transfer_id=?
                """, (rs, row) -> new TimelineEvent(UUID.nameUUIDFromBytes(("idem:" + transferId).getBytes(java.nio.charset.StandardCharsets.UTF_8)), "IDEMPOTENCY",
                "Idempotency record", "Request key persisted in PostgreSQL", ts(rs, "created_at")), transferId));
        events.addAll(jdbc.query("""
                SELECT id, effect_type, created_at FROM ledger_transactions WHERE transfer_id=?
                """, (rs, row) -> new TimelineEvent(rs.getObject("id", UUID.class), "LEDGER",
                rs.getString("effect_type").replace('_', ' '), "Double-entry ledger transaction", ts(rs, "created_at")), transferId));
        events.addAll(jdbc.query("""
                SELECT id, created_at, processed_at FROM outbox_events WHERE aggregate_id=?
                """, (rs, row) -> {
            UUID id = rs.getObject("id", UUID.class);
            return new TimelineEvent[]{
                    new TimelineEvent(id, "OUTBOX", "Outbox event committed", "Durable settlement request", ts(rs, "created_at")),
                    new TimelineEvent(id, "OUTBOX", "Outbox event acknowledged", "Processing recorded", ts(rs, "processed_at"))
            };
        }, transferId).stream().flatMap(java.util.Arrays::stream).filter(e -> e.at() != null).toList());
        events.addAll(jdbc.query("""
                SELECT id, attempt_number, started_at, completed_at, outcome
                FROM settlement_attempts WHERE transfer_id=?
                """, (rs, row) -> {
            UUID id = rs.getObject("id", UUID.class);
            int n = rs.getInt("attempt_number");
            return new TimelineEvent[]{
                    new TimelineEvent(id, "ATTEMPT", "Settlement attempt #" + n, "Dispatch started", ts(rs, "started_at")),
                    new TimelineEvent(id, "ATTEMPT", "Attempt #" + n + " → " + rs.getString("outcome"),
                            "Recorded transport outcome", ts(rs, "completed_at"))
            };
        }, transferId).stream().flatMap(java.util.Arrays::stream).filter(e -> e.at() != null).toList());
        events.addAll(jdbc.query("""
                SELECT event_id, external_status, disposition, received_at
                FROM settlement_callbacks WHERE transfer_id=?
                """, (rs, row) -> new TimelineEvent(rs.getObject("event_id", UUID.class), "CALLBACK",
                "Signed callback: " + rs.getString("external_status"),
                rs.getString("disposition"), ts(rs, "received_at")), transferId));
        events.sort(Comparator.comparing(TimelineEvent::at).thenComparing(e -> e.id().toString()));
        return events;
    }

    private static Instant ts(ResultSet rs, String name) throws SQLException {
        return ClientPortalReadController.timestamp(rs, name);
    }
    private static Integer nullableInt(ResultSet rs, String name) throws SQLException {
        int value = rs.getInt(name); return rs.wasNull() ? null : value;
    }
    private static Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name); return rs.wasNull() ? null : value;
    }
}
