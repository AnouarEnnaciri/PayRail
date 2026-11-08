package com.payrail.backend;

import com.payrail.backend.account.Account;
import com.payrail.backend.account.AccountRepository;
import com.payrail.backend.ledger.InitialFundingService;
import com.payrail.backend.outbox.OutboxProcessor;
import com.payrail.backend.outbox.OutboxStore;
import com.payrail.backend.settlement.SettlementTransport;
import com.payrail.backend.transfer.ExternalReservationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "payrail.outbox.worker.enabled=false",
        "payrail.outbox.worker.lease-seconds=4"
})
class ExternalSettlementFinalizationIntegrationTest {

    @Autowired AccountRepository accounts;
    @Autowired InitialFundingService funding;
    @Autowired ExternalReservationService reservation;
    @Autowired OutboxProcessor worker;
    @Autowired OutboxStore store;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SettlementTransport transport;

    private record Case(UUID senderId, UUID transferId, UUID requestId) { }

    private UUID testTransferId;

    @AfterEach
    void discardUnfinishedTestJob() {
        if (testTransferId == null) return;
        // Failed/timeout scenarios intentionally leave unfinished work. Do not let it
        // contaminate another test method that shares this Testcontainers database.
        jdbc.update("""
                UPDATE outbox_events
                SET status = 'DEAD_LETTER', locked_until = NULL, locked_by = NULL
                WHERE aggregate_id = ? AND status IN ('PENDING', 'PROCESSING')
                """, testTransferId);
        testTransferId = null;
    }

    private Case freshReservation() {
        Account customer = Account.customer("M9-CUST-" + UUID.randomUUID(), UUID.randomUUID());
        Account fundingAccount = Account.system("M9-FUND-" + UUID.randomUUID());
        accounts.saveAllAndFlush(List.of(customer, fundingAccount));
        funding.fund(customer.getId(), fundingAccount.getId(), 100_000L);
        var result = reservation.reserve(customer.getId(), "BANK-M9-001", 20_000L,
                UUID.randomUUID().toString());
        testTransferId = result.transferId();
        // processOne() selects the oldest available event globally. Give this test
        // fixture priority without modifying the production worker's claim logic.
        jdbc.update("""
                UPDATE outbox_events SET available_at = '-infinity'::timestamptz
                WHERE aggregate_id = ?
                """, testTransferId);
        return new Case(customer.getId(), result.transferId(), result.settlementRequestId());
    }

    private SettlementTransport.Receipt receipt(SettlementTransport.Command command, String status) {
        return new SettlementTransport.Receipt(command.settlementRequestId(), command.amountMinor(),
                command.destinationReference(), status,
                "SETTLED".equals(status) ? "SIM-" + command.settlementRequestId() : null);
    }

    private String transferState(Case sample) {
        return jdbc.queryForObject("SELECT status FROM transfers WHERE id = ?", String.class, sample.transferId());
    }

    private String jobState(Case sample) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE aggregate_id = ?",
                String.class, sample.transferId());
    }

    private long countEffect(Case sample, String type) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM ledger_transactions WHERE transfer_id = ? AND effect_type = ?
                """, Long.class, sample.transferId(), type);
    }

    private long attemptCount(Case sample) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM settlement_attempts WHERE transfer_id = ?",
                Long.class, sample.transferId());
    }

    private long ledgerBalance(String reference) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(e.signed_amount_minor), 0)
                FROM ledger_entries e JOIN accounts a ON a.id = e.account_id
                WHERE a.reference = ?
                """, Long.class, reference);
    }

    private long balance(UUID accountId) {
        return jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?",
                Long.class, accountId);
    }

    private void simulate(String status) {
        when(transport.submit(any())).thenAnswer(call -> receipt(call.getArgument(0), status));
    }

    @Test
    void verifiedSuccessMovesClearingToSettledExactlyOnce() {
        Case sample = freshReservation();
        long clearingBefore = ledgerBalance("SETTLEMENT_CLEARING");
        long settledBefore = ledgerBalance("EXTERNAL_SETTLED");
        simulate("SETTLED");

        assertTrue(worker.processOne());
        // Verify this transfer rather than assuming the global outbox queue is empty.
        assertEquals("PROCESSED", jobState(sample));
        assertEquals(1L, attemptCount(sample));
        assertEquals("SETTLED", transferState(sample));
        assertEquals(80_000L, balance(sample.senderId()));
        assertEquals(clearingBefore - 20_000L, ledgerBalance("SETTLEMENT_CLEARING"));
        assertEquals(settledBefore + 20_000L, ledgerBalance("EXTERNAL_SETTLED"));
        assertEquals(1L, countEffect(sample, "EXTERNAL_RESERVATION"));
        assertEquals(1L, countEffect(sample, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(0L, countEffect(sample, "EXTERNAL_COMPENSATION"));
        assertBalanced(sample);
        verify(transport, times(1)).submit(any());
    }

    @Test
    void definitiveRejectionCompensatesOnceWithoutSecondDebit() {
        Case sample = freshReservation();
        long clearingBefore = ledgerBalance("SETTLEMENT_CLEARING");
        simulate("REJECTED");

        assertTrue(worker.processOne());
        // Verify this transfer rather than assuming the global outbox queue is empty.
        assertEquals("PROCESSED", jobState(sample));
        assertEquals(1L, attemptCount(sample));
        assertEquals("FAILED", transferState(sample));
        assertEquals(100_000L, balance(sample.senderId()));
        assertEquals(clearingBefore - 20_000L, ledgerBalance("SETTLEMENT_CLEARING"));
        assertEquals(0L, countEffect(sample, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(1L, countEffect(sample, "EXTERNAL_COMPENSATION"));
        assertBalanced(sample);
    }

    @Test
    void timeoutDoesNotCompensateOrFinalizeAnything() {
        Case sample = freshReservation();
        when(transport.submit(any())).thenThrow(new org.springframework.web.client.ResourceAccessException("timeout"));

        assertTrue(worker.processOne());
        assertEquals("PENDING_SETTLEMENT", transferState(sample));
        assertEquals("PENDING", jobState(sample));
        assertEquals(80_000L, balance(sample.senderId()));
        assertEquals(0L, countEffect(sample, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(0L, countEffect(sample, "EXTERNAL_COMPENSATION"));
    }

    @Test
    void databasePreventsAnySecondTerminalLedgerEffect() {
        Case sample = freshReservation();
        simulate("SETTLED");
        assertTrue(worker.processOne());

        assertThrows(DuplicateKeyException.class, () -> jdbc.update("""
                INSERT INTO ledger_transactions(id, transfer_id, effect_type)
                VALUES (?, ?, 'EXTERNAL_COMPENSATION')
                """, UUID.randomUUID(), sample.transferId()));
        assertEquals(1L, countEffect(sample, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(0L, countEffect(sample, "EXTERNAL_COMPENSATION"));
    }

    @Test
    void staleWorkerCannotAcknowledgeOrFinalizeAfterLeaseReclaim() {
        Case sample = freshReservation();
        OutboxStore.Claim stale = store.claimNext();
        assertNotNull(stale);
        jdbc.update("""
                UPDATE outbox_events SET locked_until = now() - interval '1 second'
                WHERE id = ?
                """, stale.eventId());
        OutboxStore.Claim current = store.claimNext();
        assertNotNull(current);

        OutboxStore.Completion settled = new OutboxStore.Completion(
                "SETTLED", 200, null, "SIM-" + sample.requestId(),
                null, true, false, 10L);
        assertThrows(IllegalStateException.class, () -> store.complete(stale, settled));
        assertEquals("PROCESSING", jobState(sample));
        assertEquals("PENDING_SETTLEMENT", transferState(sample));
        assertEquals(0L, countEffect(sample, "EXTERNAL_SETTLEMENT_FINALIZATION"));

        store.complete(current, settled);
        assertEquals("PROCESSED", jobState(sample));
        assertEquals("SETTLED", transferState(sample));
        assertEquals(1L, countEffect(sample, "EXTERNAL_SETTLEMENT_FINALIZATION"));
    }

    @Test
    void failedFinalizationRollsBackOutboxAckAndAttemptReceipt() {
        Case sample = freshReservation();
        OutboxStore.Claim claim = store.claimNext();
        assertNotNull(claim);
        // Simulate inconsistent internal state: do not allow a second financial effect.
        jdbc.update("UPDATE transfers SET status = 'FAILED' WHERE id = ?", sample.transferId());
        OutboxStore.Completion settled = new OutboxStore.Completion(
                "SETTLED", 200, null, "SIM-" + sample.requestId(),
                null, true, false, 15L);

        assertThrows(IllegalStateException.class, () -> store.complete(claim, settled));
        assertEquals("PROCESSING", jobState(sample));
        assertEquals("IN_FLIGHT", jdbc.queryForObject(
                "SELECT outcome FROM settlement_attempts WHERE id = ?",
                String.class, claim.attemptId()));
        assertEquals(0L, countEffect(sample, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(0L, countEffect(sample, "EXTERNAL_COMPENSATION"));
    }

    @Test
    void tamperedOutboxPayloadCannotFinalizeDifferentAmount() {
        Case sample = freshReservation();
        jdbc.update("""
                UPDATE outbox_events
                SET payload_json = jsonb_set(payload_json, '{amountMinor}', '30000'::jsonb)
                WHERE aggregate_id = ?
                """, sample.transferId());
        simulate("SETTLED");

        assertThrows(IllegalStateException.class, () -> worker.processOne());
        assertEquals("PENDING_SETTLEMENT", transferState(sample));
        assertEquals("PROCESSING", jobState(sample));
        assertEquals(80_000L, balance(sample.senderId()));
        assertEquals(0L, countEffect(sample, "EXTERNAL_SETTLEMENT_FINALIZATION"));
    }

    @Test
    void expiredLeaseCannotFinalizeEvenIfNoOtherWorkerHasReclaimedIt() {
        Case sample = freshReservation();
        OutboxStore.Claim claim = store.claimNext();
        assertNotNull(claim);
        jdbc.update("""
                UPDATE outbox_events SET locked_until = now() - interval '1 second' WHERE id = ?
                """, claim.eventId());
        OutboxStore.Completion receipt = new OutboxStore.Completion(
                "SETTLED", 200, null, "SIM-" + sample.requestId(),
                null, true, false, 5L);
        assertThrows(IllegalStateException.class, () -> store.complete(claim, receipt));
        assertEquals("PENDING_SETTLEMENT", transferState(sample));
        assertEquals(0L, countEffect(sample, "EXTERNAL_SETTLEMENT_FINALIZATION"));
    }

    private void assertBalanced(Case sample) {
        List<Long> totals = jdbc.query("""
                SELECT SUM(e.signed_amount_minor) AS total
                FROM ledger_entries e
                JOIN ledger_transactions t ON t.id = e.ledger_transaction_id
                WHERE t.transfer_id = ?
                GROUP BY t.id
                """, (rs, idx) -> rs.getLong("total"), sample.transferId());
        assertEquals(2, totals.size());
        assertTrue(totals.stream().allMatch(total -> total == 0L));
    }
}
