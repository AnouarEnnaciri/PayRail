package com.payrail.backend;

import com.payrail.backend.account.Account;
import com.payrail.backend.account.AccountRepository;
import com.payrail.backend.ledger.InitialFundingService;
import com.payrail.backend.outbox.OutboxProcessor;
import com.payrail.backend.settlement.ExternalSettlementFinalizationService;
import com.payrail.backend.settlement.SettlementTransport;
import com.payrail.backend.transfer.ExternalReservationService;
import com.payrail.backend.transfer.InternalTransferService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** PostgreSQL/Testcontainers proof for M11 transition safety, ledger preservation and recovery. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "payrail.outbox.worker.enabled=false",
        "payrail.outbox.worker.max-attempts=1",
        "payrail.outbox.worker.lease-seconds=4"
})
class TransferStateMachineIntegrationTest {

    @Autowired AccountRepository accounts;
    @Autowired InitialFundingService funding;
    @Autowired ExternalReservationService reservations;
    @Autowired InternalTransferService internalTransfers;
    @Autowired ExternalSettlementFinalizationService finalizer;
    @Autowired OutboxProcessor processor;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @MockitoBean SettlementTransport transport;

    private UUID pendingTransferToClean;
    private record Scenario(UUID customerId, UUID transferId, UUID settlementRequestId) { }

    @AfterEach
    void discardOwnUnfinishedOutbox() {
        if (pendingTransferToClean != null) {
            jdbc.update("""
                    UPDATE outbox_events
                    SET status = 'DEAD_LETTER', locked_until = NULL, locked_by = NULL
                    WHERE aggregate_id = ? AND status IN ('PENDING', 'PROCESSING')
                    """, pendingTransferToClean);
            pendingTransferToClean = null;
        }
    }

    private Scenario reservation() {
        Account sender = Account.customer("M11-CUST-" + UUID.randomUUID(), UUID.randomUUID());
        Account fundingSource = Account.system("M11-FUND-" + UUID.randomUUID());
        accounts.saveAllAndFlush(List.of(sender, fundingSource));
        funding.fund(sender.getId(), fundingSource.getId(), 100_000L);
        var result = reservations.reserve(sender.getId(), "BANK-M11-001", 20_000L, UUID.randomUUID().toString());
        pendingTransferToClean = result.transferId();
        jdbc.update("""
                UPDATE outbox_events SET available_at = '-infinity'::timestamptz
                WHERE aggregate_id = ?
                """, result.transferId());
        return new Scenario(sender.getId(), result.transferId(), result.settlementRequestId());
    }

    private String state(Scenario s) {
        return jdbc.queryForObject("SELECT status FROM transfers WHERE id = ?", String.class, s.transferId());
    }

    private long balance(Scenario s) {
        return jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?", Long.class, s.customerId());
    }

    private long effects(Scenario s, String effectType) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM ledger_transactions WHERE transfer_id = ? AND effect_type = ?
                """, Long.class, s.transferId(), effectType);
    }

    private void assertBalanced(Scenario s) {
        List<Long> sums = jdbc.query("""
                SELECT COALESCE(sum(e.signed_amount_minor), 0)
                FROM ledger_entries e
                JOIN ledger_transactions t ON t.id = e.ledger_transaction_id
                WHERE t.transfer_id = ? GROUP BY t.id
                """, (rs, row) -> rs.getLong(1), s.transferId());
        assertTrue(sums.stream().allMatch(total -> total == 0L));
    }

    private void forceUncertainOutcome(Scenario s) {
        when(transport.submit(any())).thenThrow(new ResourceAccessException("simulated uncertain timeout"));
        when(transport.get(s.settlementRequestId()))
                .thenThrow(new ResourceAccessException("simulated status lookup timeout"));
        assertTrue(processor.processOne());
        assertEquals("RECONCILIATION_REQUIRED", state(s));
        assertEquals("DEAD_LETTER", jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE aggregate_id = ?", String.class, s.transferId()));
        assertEquals(80_000L, balance(s));
        assertEquals(1L, effects(s, "EXTERNAL_RESERVATION"));
        assertEquals(0L, effects(s, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(0L, effects(s, "EXTERNAL_COMPENSATION"));
    }

    /** Models the financial finalization step after a trusted terminal receipt has been verified. */
    private void finalizeVerified(Scenario s, String terminalExternalStatus) {
        String reference = "SETTLED".equals(terminalExternalStatus) ? "SIM-" + s.settlementRequestId() : null;
        new TransactionTemplate(txManager).executeWithoutResult(ignored -> finalizer.apply(
                s.transferId(), s.settlementRequestId(), 20_000L, "BANK-M11-001",
                terminalExternalStatus, reference));
    }

    @Test
    void reconciledVerifiedSuccessPostsOneBalancedFinalizationWithoutDoubleDebit() {
        Scenario s = reservation();
        forceUncertainOutcome(s);
        finalizeVerified(s, "SETTLED");

        assertEquals("SETTLED", state(s));
        assertEquals(80_000L, balance(s));
        assertEquals(1L, effects(s, "EXTERNAL_RESERVATION"));
        assertEquals(1L, effects(s, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(0L, effects(s, "EXTERNAL_COMPENSATION"));
        assertBalanced(s);

        assertThrows(IllegalStateException.class, () -> finalizeVerified(s, "SETTLED"));
        assertThrows(IllegalStateException.class, () -> finalizeVerified(s, "REJECTED"));
        assertEquals(1L, effects(s, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(0L, effects(s, "EXTERNAL_COMPENSATION"));
    }

    @Test
    void reconciledVerifiedRejectionCompensatesOnceAndCannotLaterSettle() {
        Scenario s = reservation();
        forceUncertainOutcome(s);
        finalizeVerified(s, "REJECTED");

        assertEquals("FAILED", state(s));
        assertEquals(100_000L, balance(s));
        assertEquals(1L, effects(s, "EXTERNAL_COMPENSATION"));
        assertEquals(0L, effects(s, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertBalanced(s);

        assertThrows(IllegalStateException.class, () -> finalizeVerified(s, "SETTLED"));
        assertEquals(1L, effects(s, "EXTERNAL_COMPENSATION"));
    }

    @Test
    void rawSqlCannotReopenSettledTransfer() {
        Scenario s = reservation();
        when(transport.submit(any())).thenAnswer(invocation -> {
            SettlementTransport.Command command = invocation.getArgument(0);
            return new SettlementTransport.Receipt(command.settlementRequestId(), command.amountMinor(),
                    command.destinationReference(), "SETTLED", "SIM-" + command.settlementRequestId());
        });
        assertTrue(processor.processOne());
        assertEquals("SETTLED", state(s));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE transfers SET status = 'PENDING_SETTLEMENT' WHERE id = ?", s.transferId()));
        assertEquals("SETTLED", state(s));
        assertEquals(1L, effects(s, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(80_000L, balance(s));
    }

    @Test
    void rawSqlCannotTurnReconciledTransferBackIntoPending() {
        Scenario s = reservation();
        forceUncertainOutcome(s);
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE transfers SET status = 'PENDING_SETTLEMENT' WHERE id = ?", s.transferId()));
        assertEquals("RECONCILIATION_REQUIRED", state(s));
        assertEquals(0L, effects(s, "EXTERNAL_COMPENSATION"));
        assertEquals(80_000L, balance(s));
    }

    @Test
    void rawSqlCannotChangeFailedTerminalOutcomeIntoSettled() {
        Scenario s = reservation();
        when(transport.submit(any())).thenAnswer(invocation -> {
            SettlementTransport.Command command = invocation.getArgument(0);
            return new SettlementTransport.Receipt(command.settlementRequestId(), command.amountMinor(),
                    command.destinationReference(), "REJECTED", null);
        });
        assertTrue(processor.processOne());
        assertEquals("FAILED", state(s));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE transfers SET status = 'SETTLED' WHERE id = ?", s.transferId()));
        assertEquals("FAILED", state(s));
        assertEquals(1L, effects(s, "EXTERNAL_COMPENSATION"));
        assertEquals(100_000L, balance(s));
    }

    @Test
    void completedInternalTransferCannotBecomeFailedViaRawSql() {
        Account sender = Account.customer("M11-IN-" + UUID.randomUUID(), UUID.randomUUID());
        Account receiver = Account.customer("M11-IN-" + UUID.randomUUID(), UUID.randomUUID());
        Account source = Account.system("M11-IN-FUND-" + UUID.randomUUID());
        accounts.saveAllAndFlush(List.of(sender, receiver, source));
        funding.fund(sender.getId(), source.getId(), 10_000L);
        UUID transferId = internalTransfers.transfer(sender.getId(), receiver.getId(), 1_000L);
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT status FROM transfers WHERE id = ?", String.class, transferId));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE transfers SET status = 'FAILED' WHERE id = ?", transferId));
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT status FROM transfers WHERE id = ?", String.class, transferId));
        assertEquals(9_000L, jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?",
                Long.class, sender.getId()));
        assertEquals(1_000L, jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?",
                Long.class, receiver.getId()));
    }
}
