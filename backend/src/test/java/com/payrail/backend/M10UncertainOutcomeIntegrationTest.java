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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/** Testcontainers/PostgreSQL proof of M10 financial invariants. No live simulator required. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "payrail.outbox.worker.enabled=false",
        "payrail.outbox.worker.max-attempts=2",
        "payrail.outbox.worker.lease-seconds=4",
        "payrail.outbox.worker.base-backoff-seconds=1",
        "payrail.outbox.worker.max-backoff-seconds=2"
})
class M10UncertainOutcomeIntegrationTest {

    @Autowired AccountRepository accounts;
    @Autowired InitialFundingService funding;
    @Autowired ExternalReservationService reservations;
    @Autowired OutboxProcessor processor;
    @Autowired OutboxStore store;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SettlementTransport transport;

    private UUID testTransferId;

    private record Scenario(UUID customerId, UUID transferId, UUID requestId) { }

    @AfterEach
    void cleanUpIncompleteScenario() {
        if (testTransferId != null) {
            jdbc.update("""
                    UPDATE outbox_events
                    SET status = 'DEAD_LETTER', locked_until = NULL, locked_by = NULL
                    WHERE aggregate_id = ? AND status IN ('PENDING', 'PROCESSING')
                    """, testTransferId);
        }
        testTransferId = null;
    }

    private Scenario reserve() {
        Account customer = Account.customer("M10-CUSTOMER-" + UUID.randomUUID(), UUID.randomUUID());
        Account fundingAccount = Account.system("M10-FUNDING-" + UUID.randomUUID());
        accounts.saveAllAndFlush(List.of(customer, fundingAccount));
        funding.fund(customer.getId(), fundingAccount.getId(), 100_000L);
        var reservation = reservations.reserve(customer.getId(), "BANK-M10-001", 20_000L,
                UUID.randomUUID().toString());
        testTransferId = reservation.transferId();
        jdbc.update("""
                UPDATE outbox_events SET available_at = '-infinity'::timestamptz
                WHERE aggregate_id = ?
                """, testTransferId);
        return new Scenario(customer.getId(), reservation.transferId(), reservation.settlementRequestId());
    }

    private SettlementTransport.Receipt settled(Scenario s) {
        return new SettlementTransport.Receipt(
                s.requestId(), 20_000L, "BANK-M10-001", "SETTLED", "SIM-" + s.requestId());
    }

    private String transferStatus(Scenario s) {
        return jdbc.queryForObject("SELECT status FROM transfers WHERE id = ?",
                String.class, s.transferId());
    }

    private String outboxStatus(Scenario s) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE aggregate_id = ?",
                String.class, s.transferId());
    }

    private long balance(Scenario s) {
        return jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?",
                Long.class, s.customerId());
    }

    private long effectCount(Scenario s, String type) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM ledger_transactions WHERE transfer_id = ? AND effect_type = ?
                """, Long.class, s.transferId(), type);
    }

    private long attemptCount(Scenario s) {
        return jdbc.queryForObject("SELECT count(*) FROM settlement_attempts WHERE transfer_id = ?",
                Long.class, s.transferId());
    }

    private void makeDue(Scenario s) {
        jdbc.update("UPDATE outbox_events SET available_at = now() - interval '2 seconds' WHERE aggregate_id = ?",
                s.transferId());
    }

    private void assertNoTerminalEffect(Scenario s) {
        assertEquals(0L, effectCount(s, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(0L, effectCount(s, "EXTERNAL_COMPENSATION"));
        assertEquals(1L, effectCount(s, "EXTERNAL_RESERVATION"));
        assertEquals(80_000L, balance(s));
    }

    @Test
    void timeoutAfterProcessingIsRecoveredByStatusLookupAndFinalizedExactlyOnce() {
        Scenario s = reserve();
        when(transport.submit(any())).thenThrow(new ResourceAccessException("response lost after commit"));
        when(transport.get(s.requestId())).thenReturn(settled(s));

        assertTrue(processor.processOne());
        assertEquals("PROCESSED", outboxStatus(s));
        assertEquals("SETTLED", transferStatus(s));
        assertEquals(1L, attemptCount(s));
        assertEquals(1L, effectCount(s, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(0L, effectCount(s, "EXTERNAL_COMPENSATION"));
        assertEquals(80_000L, balance(s));
        verify(transport, times(1)).submit(argThat(c -> s.requestId().equals(c.settlementRequestId())));
        verify(transport, times(1)).get(s.requestId());
        assertEquals("PROCESSED", outboxStatus(s), "Terminal receipt must close its outbox event");
    }

    @Test
    void gatewayTimeoutIsAlsoAmbiguousAndCanRecoverSettledReceipt() {
        Scenario s = reserve();
        when(transport.submit(any()))
                .thenThrow(new HttpServerErrorException(HttpStatus.GATEWAY_TIMEOUT));
        when(transport.get(s.requestId())).thenReturn(settled(s));

        assertTrue(processor.processOne());
        assertEquals("SETTLED", transferStatus(s));
        assertEquals("PROCESSED", outboxStatus(s));
        assertEquals(1L, effectCount(s, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(0L, effectCount(s, "EXTERNAL_COMPENSATION"));
        verify(transport, times(1)).get(s.requestId());
    }

    @Test
    void mismatchedStatusLookupCannotCauseIncorrectFinancialFinalization() {
        Scenario s = reserve();
        when(transport.submit(any())).thenThrow(new ResourceAccessException("timeout"));
        when(transport.get(s.requestId())).thenReturn(new SettlementTransport.Receipt(
                s.requestId(), 21_000L, "BANK-M10-001", "SETTLED", "SIM-INVALID"));

        assertTrue(processor.processOne());
        assertEquals("PENDING", outboxStatus(s));
        assertEquals("PENDING_SETTLEMENT", transferStatus(s));
        assertEquals("INVALID_RESPONSE", jdbc.queryForObject("""
                SELECT outcome FROM settlement_attempts WHERE transfer_id = ?
                """, String.class, s.transferId()));
        assertNoTerminalEffect(s);
    }

    @Test
    void timeoutWithVerifiedRejectionCompensatesOnlyAfterExternalProof() {
        Scenario s = reserve();
        when(transport.submit(any())).thenThrow(new ResourceAccessException("response lost"));
        when(transport.get(s.requestId())).thenReturn(new SettlementTransport.Receipt(
                s.requestId(), 20_000L, "BANK-M10-001", "REJECTED", null));

        assertTrue(processor.processOne());
        assertEquals("PROCESSED", outboxStatus(s));
        assertEquals("FAILED", transferStatus(s));
        assertEquals(1L, effectCount(s, "EXTERNAL_COMPENSATION"));
        assertEquals(0L, effectCount(s, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(100_000L, balance(s));
        verify(transport, times(1)).get(s.requestId());
    }

    @Test
    void timeoutBeforeProcessingNotFoundIsNotFailureAndRetryUsesStableIdentity() {
        Scenario s = reserve();
        when(transport.submit(any()))
                .thenThrow(new ResourceAccessException("timeout before processing"))
                .thenReturn(settled(s));
        when(transport.get(s.requestId()))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        assertTrue(processor.processOne());
        assertEquals("PENDING_SETTLEMENT", transferStatus(s));
        assertEquals("PENDING", outboxStatus(s));
        assertEquals("NOT_FOUND", jdbc.queryForObject("""
                SELECT outcome FROM settlement_attempts
                WHERE transfer_id = ? AND attempt_number = 1
                """, String.class, s.transferId()));
        assertNoTerminalEffect(s);

        makeDue(s);
        assertTrue(processor.processOne());
        assertEquals("SETTLED", transferStatus(s));
        assertEquals("PROCESSED", outboxStatus(s));
        assertEquals(2L, attemptCount(s));
        assertEquals(1L, effectCount(s, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(0L, effectCount(s, "EXTERNAL_COMPENSATION"));
        verify(transport, times(2)).submit(argThat(c -> s.requestId().equals(c.settlementRequestId())));
        verify(transport, times(1)).get(s.requestId());
    }

    @Test
    void exhaustedUncertaintyEscalatesToReconciliationWithoutRefund() {
        Scenario s = reserve();
        when(transport.submit(any())).thenThrow(new ResourceAccessException("timeout"));
        when(transport.get(s.requestId()))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        assertTrue(processor.processOne());
        assertEquals("PENDING_SETTLEMENT", transferStatus(s));
        makeDue(s);
        assertTrue(processor.processOne());

        assertEquals("DEAD_LETTER", outboxStatus(s));
        assertEquals("RECONCILIATION_REQUIRED", transferStatus(s));
        assertEquals(2L, attemptCount(s));
        assertNoTerminalEffect(s);
        verify(transport, times(2)).submit(argThat(c -> s.requestId().equals(c.settlementRequestId())));
        verify(transport, times(2)).get(s.requestId());
    }

    @Test
    void protocolConflictRequiresReconciliationNotFinancialRejection() {
        Scenario s = reserve();
        when(transport.submit(any())).thenThrow(new HttpClientErrorException(HttpStatus.CONFLICT));

        assertTrue(processor.processOne());
        assertEquals("DEAD_LETTER", outboxStatus(s));
        assertEquals("RECONCILIATION_REQUIRED", transferStatus(s));
        assertNoTerminalEffect(s);
        verify(transport, never()).get(any());
    }

    @Test
    void finalAttemptLeaseExpiryAfterCrashEscalatesAtomically() {
        Scenario s = reserve();
        OutboxStore.Claim first = store.claimNext();
        assertNotNull(first);
        jdbc.update("UPDATE outbox_events SET locked_until = now() - interval '1 second' WHERE id = ?",
                first.eventId());
        OutboxStore.Claim second = store.claimNext();
        assertNotNull(second);
        assertEquals(s.requestId(), first.settlementRequestId());
        assertEquals(s.requestId(), second.settlementRequestId());
        jdbc.update("UPDATE outbox_events SET locked_until = now() - interval '1 second' WHERE id = ?",
                second.eventId());

        assertNull(store.claimNext());
        assertEquals("DEAD_LETTER", outboxStatus(s));
        assertEquals("RECONCILIATION_REQUIRED", transferStatus(s));
        assertNoTerminalEffect(s);
        assertEquals(2L, attemptCount(s));
    }
}
