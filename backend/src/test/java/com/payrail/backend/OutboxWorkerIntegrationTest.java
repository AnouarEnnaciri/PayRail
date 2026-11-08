package com.payrail.backend;

import com.payrail.backend.account.Account;
import com.payrail.backend.account.AccountRepository;
import com.payrail.backend.ledger.InitialFundingService;
import com.payrail.backend.outbox.OutboxProcessor;
import com.payrail.backend.outbox.OutboxStore;
import com.payrail.backend.settlement.SettlementTransport;
import com.payrail.backend.transfer.ExternalReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/** Real PostgreSQL/Testcontainers, controlled external transport. No simulator required for this unit of reliability. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "payrail.outbox.worker.enabled=false",
        "payrail.outbox.worker.max-attempts=3",
        "payrail.outbox.worker.lease-seconds=4",
        "payrail.outbox.worker.base-backoff-seconds=1",
        "payrail.outbox.worker.max-backoff-seconds=4"
})
class OutboxWorkerIntegrationTest {

    @Autowired AccountRepository accounts;
    @Autowired InitialFundingService funding;
    @Autowired ExternalReservationService reservation;
    @Autowired OutboxStore store;
    @Autowired OutboxProcessor processor;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SettlementTransport transport;

    private record Created(UUID senderId, UUID transferId, UUID requestId) { }

    private Created createReservation() {
        Account customer = Account.customer("M8-CUSTOMER-" + UUID.randomUUID(), UUID.randomUUID());
        Account source = Account.system("M8-FUNDING-" + UUID.randomUUID());
        accounts.saveAllAndFlush(List.of(customer, source));
        funding.fund(customer.getId(), source.getId(), 100_000L);
        var result = reservation.reserve(customer.getId(), "BANK-OUTBOX-001", 20_000L,
                UUID.randomUUID().toString());
        return new Created(customer.getId(), result.transferId(), result.settlementRequestId());
    }

    private SettlementTransport.Receipt receipt(SettlementTransport.Command c, String status) {
        return new SettlementTransport.Receipt(c.settlementRequestId(), c.amountMinor(),
                c.destinationReference(), status,
                "SETTLED".equals(status) ? "SIM-" + c.settlementRequestId() : null);
    }

    private String status(UUID transferId) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE aggregate_id = ?",
                String.class, transferId);
    }

    private int attempts(UUID transferId) {
        return jdbc.queryForObject("SELECT count(*) FROM settlement_attempts WHERE transfer_id = ?",
                Integer.class, transferId);
    }

    private void makeAvailable(UUID transferId) {
        jdbc.update("UPDATE outbox_events SET available_at = now() - interval '2 seconds' WHERE aggregate_id = ?",
                transferId);
    }

    @Test
    void successfulDispatchFinalizesExactlyOneFinancialEffect() {
        Created created = createReservation();
        when(transport.submit(any())).thenAnswer(call -> receipt(call.getArgument(0), "SETTLED"));

        assertTrue(processor.processOne());
        assertEquals("PROCESSED", status(created.transferId()));
        assertEquals(1, attempts(created.transferId()));
        assertEquals("SETTLED", jdbc.queryForObject(
                "SELECT outcome FROM settlement_attempts WHERE transfer_id = ?", String.class, created.transferId()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM settlement_attempts WHERE transfer_id = ? AND completed_at IS NOT NULL",
                Integer.class, created.transferId()));
        assertEquals(80_000L, accounts.findById(created.senderId()).orElseThrow().getBalanceMinor());
        assertEquals("SETTLED", jdbc.queryForObject(
                "SELECT status FROM transfers WHERE id = ?", String.class, created.transferId()));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions WHERE transfer_id = ? AND effect_type = 'EXTERNAL_SETTLEMENT_FINALIZATION'",
                Long.class, created.transferId()));
        assertEquals(0L, jdbc.queryForObject("""
                SELECT COALESCE(SUM(e.signed_amount_minor),0)
                FROM ledger_entries e JOIN ledger_transactions t ON e.ledger_transaction_id = t.id
                WHERE t.transfer_id = ? AND t.effect_type = 'EXTERNAL_RESERVATION'
                """, Long.class, created.transferId()));
        verify(transport).submit(argThat(c -> c.settlementRequestId().equals(created.requestId())));
    }

    @Test
    void networkTimeoutRetriesUsingTheSameSettlementIdentityWithoutSecondDebit() {
        Created created = createReservation();
        when(transport.submit(any()))
                .thenThrow(new ResourceAccessException("lost response"))
                .thenAnswer(call -> receipt(call.getArgument(0), "SETTLED"));

        assertTrue(processor.processOne());
        assertEquals("PENDING", status(created.transferId()));
        assertEquals("NETWORK_ERROR", jdbc.queryForObject(
                "SELECT outcome FROM settlement_attempts WHERE transfer_id = ? AND attempt_number = 1",
                String.class, created.transferId()));
        makeAvailable(created.transferId());
        assertTrue(processor.processOne());

        assertEquals("PROCESSED", status(created.transferId()));
        assertEquals(2, attempts(created.transferId()));
        verify(transport, times(2)).submit(argThat(c -> c.settlementRequestId().equals(created.requestId())));
        assertEquals(80_000L, accounts.findById(created.senderId()).orElseThrow().getBalanceMinor());
    }

    @Test
    void expiredLeaseCanBeReclaimedAfterSimulatedWorkerCrash() {
        Created created = createReservation();
        OutboxStore.Claim abandoned = store.claimNext();
        assertNotNull(abandoned);
        assertNull(store.claimNext(), "The live lease must exclude other claims");

        // A worker was terminated after claim committed, before any HTTP result was stored.
        jdbc.update("UPDATE outbox_events SET locked_until = now() - interval '1 second' WHERE id = ?",
                abandoned.eventId());
        when(transport.submit(any())).thenAnswer(call -> receipt(call.getArgument(0), "SETTLED"));
        assertTrue(processor.processOne());
        assertEquals("PROCESSED", status(created.transferId()));
        assertEquals(2, attempts(created.transferId()));
        assertEquals("IN_FLIGHT", jdbc.queryForObject(
                "SELECT outcome FROM settlement_attempts WHERE id = ?", String.class, abandoned.attemptId()));
        assertEquals(created.requestId(), abandoned.settlementRequestId());
    }

    @Test
    void delayedResultIsQueriedByStableIdBeforeBeingMarkedProcessed() {
        Created created = createReservation();
        when(transport.submit(any())).thenAnswer(call -> receipt(call.getArgument(0), "PENDING"));
        when(transport.get(any())).thenReturn(new SettlementTransport.Receipt(
                created.requestId(), 20_000L, "BANK-OUTBOX-001", "SETTLED", "SIM-" + created.requestId()));

        assertTrue(processor.processOne());
        assertEquals("PROCESSED", status(created.transferId()));
        verify(transport).get(created.requestId());
    }

    @Test
    void exhaustedRetriesDeadLetterOnlyTheJobNotTheFinancialTransfer() {
        Created created = createReservation();
        when(transport.submit(any())).thenThrow(new ResourceAccessException("timeout"));
        for (int i = 0; i < 3; i++) {
            assertTrue(processor.processOne());
            if (i < 2) makeAvailable(created.transferId());
        }
        assertEquals("DEAD_LETTER", status(created.transferId()));
        assertEquals(3, attempts(created.transferId()));
        assertEquals("PENDING_SETTLEMENT", jdbc.queryForObject(
                "SELECT status FROM transfers WHERE id = ?", String.class, created.transferId()));
        assertEquals(80_000L, accounts.findById(created.senderId()).orElseThrow().getBalanceMinor());
    }

    @Test
    void conflictIsEscalatedWithoutInventingFinancialRejection() {
        Created created = createReservation();
        when(transport.submit(any())).thenThrow(new HttpClientErrorException(HttpStatus.CONFLICT));
        assertTrue(processor.processOne());
        assertEquals("DEAD_LETTER", status(created.transferId()));
        assertEquals("PENDING_SETTLEMENT", jdbc.queryForObject(
                "SELECT status FROM transfers WHERE id = ?", String.class, created.transferId()));
    }

    @Test
    void unexpectedReceiptIsRejectedAndSafelyRetried() {
        Created created = createReservation();
        when(transport.submit(any()))
                .thenAnswer(call -> new SettlementTransport.Receipt(
                        UUID.randomUUID(), 20_000L, "BANK-OUTBOX-001", "SETTLED", "WRONG"))
                .thenAnswer(call -> receipt(call.getArgument(0), "SETTLED"));
        assertTrue(processor.processOne());
        assertEquals("PENDING", status(created.transferId()));
        assertEquals("INVALID_RESPONSE", jdbc.queryForObject(
                "SELECT outcome FROM settlement_attempts WHERE transfer_id = ? AND attempt_number = 1",
                String.class, created.transferId()));
        makeAvailable(created.transferId());
        assertTrue(processor.processOne());
        assertEquals("PROCESSED", status(created.transferId()));
    }

    @Test
    void concurrentClaimsCannotAcquireTheSameOutboxJob() throws Exception {
        Created created = createReservation();
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<OutboxStore.Claim> one = pool.submit(() -> {
                start.await();
                return store.claimNext();
            });
            Future<OutboxStore.Claim> two = pool.submit(() -> {
                start.await();
                return store.claimNext();
            });
            start.countDown();
            OutboxStore.Claim first = one.get(20, TimeUnit.SECONDS);
            OutboxStore.Claim second = two.get(20, TimeUnit.SECONDS);
            assertTrue((first == null) != (second == null), "One and only one claim must win");
            OutboxStore.Claim owned = first != null ? first : second;
            assertEquals(created.requestId(), owned.settlementRequestId());
            assertEquals(1, attempts(created.transferId()));
            // Finish the test fixture through lease recovery, without leaving a claimable job behind.
            jdbc.update("UPDATE outbox_events SET locked_until = now() - interval '1 second' WHERE id = ?",
                    owned.eventId());
            when(transport.submit(any())).thenAnswer(call -> receipt(call.getArgument(0), "SETTLED"));
            assertTrue(processor.processOne());
            assertEquals("PROCESSED", status(created.transferId()));
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

}
