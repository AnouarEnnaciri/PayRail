package com.payrail.backend;


import io.micrometer.core.instrument.MeterRegistry;
import com.payrail.backend.account.Account;
import com.payrail.backend.account.AccountRepository;
import com.payrail.backend.ledger.InitialFundingService;
import com.payrail.backend.outbox.OutboxProcessor;
import com.payrail.backend.outbox.OutboxStore;
import com.payrail.backend.reconciliation.ReconciliationResolutionService;
import com.payrail.backend.reconciliation.ReconciliationService;
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
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Real PostgreSQL/Testcontainers, deterministic process-failure injection.
 * New OutboxProcessor instances simulate new workers; these tests do NOT kill a JVM.
 * The accompanying manual runbook covers genuine application process restarts.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "payrail.outbox.worker.enabled=false",
        "payrail.outbox.worker.max-attempts=2",
        "payrail.outbox.worker.lease-seconds=4",
        "payrail.outbox.worker.base-backoff-seconds=1",
        "payrail.outbox.worker.max-backoff-seconds=2",
        "payrail.reconciliation.max-external-checks=10000"
})
class OutboxRecoveryIntegrationTest {

    @Autowired AccountRepository accounts;
    @Autowired InitialFundingService funding;
    @Autowired ExternalReservationService reservations;
    @Autowired OutboxStore store;
    @Autowired OutboxProcessor processor;
    @Autowired ReconciliationService reconciliation;
    @Autowired ReconciliationResolutionService resolution;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meterRegistry;
    @MockitoBean SettlementTransport transport;

    private UUID testTransferId;
    private record Scenario(UUID customerId, UUID transferId, UUID requestId) { }

    @AfterEach
    void discardOnlyThisTestsUnfinishedOutbox() {
        if (testTransferId != null) {
            jdbc.update("""
                    UPDATE outbox_events SET status='DEAD_LETTER',
                        locked_until=NULL, locked_by=NULL
                    WHERE aggregate_id=? AND status IN ('PENDING', 'PROCESSING')
                    """, testTransferId);
            testTransferId = null;
        }
    }

    private Scenario reserve() {
        Account customer = Account.customer("M14-CUST-" + UUID.randomUUID(), UUID.randomUUID());
        Account source = Account.system("M14-FUND-" + UUID.randomUUID());
        accounts.saveAllAndFlush(List.of(customer, source));
        funding.fund(customer.getId(), source.getId(), 100_000L);
        var reserved = reservations.reserve(customer.getId(), "BANK-M14", 20_000L,
                UUID.randomUUID().toString());
        testTransferId = reserved.transferId();
        jdbc.update("""
                UPDATE outbox_events SET available_at='-infinity'::timestamptz
                WHERE aggregate_id=?
                """, testTransferId);
        return new Scenario(customer.getId(), reserved.transferId(), reserved.settlementRequestId());
    }

    private SettlementTransport.Receipt settled(Scenario s) {
        return new SettlementTransport.Receipt(
                s.requestId(), 20_000L, "BANK-M14", "SETTLED", "SIM-" + s.requestId());
    }

    private void expire(OutboxStore.Claim claim) {
        assertEquals(1, jdbc.update("""
                UPDATE outbox_events SET locked_until=now() - interval '1 second'
                WHERE id=? AND status='PROCESSING'
                """, claim.eventId()));
    }

    private String jobState(Scenario s) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE aggregate_id=?",
                String.class, s.transferId());
    }

    private String transferState(Scenario s) {
        return jdbc.queryForObject("SELECT status FROM transfers WHERE id=?",
                String.class, s.transferId());
    }

    private String attemptOutcome(Scenario s, int number) {
        return jdbc.queryForObject("""
                SELECT outcome FROM settlement_attempts WHERE transfer_id=? AND attempt_number=?
                """, String.class, s.transferId(), number);
    }

    private long customerBalance(Scenario s) {
        return jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id=?",
                Long.class, s.customerId());
    }

    private long effects(Scenario s, String type) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM ledger_transactions WHERE transfer_id=? AND effect_type=?
                """, Long.class, s.transferId(), type);
    }

    private void assertUnresolvedWithoutRefund(Scenario s) {
        assertEquals(80_000L, customerBalance(s));
        assertEquals(1L, effects(s, "EXTERNAL_RESERVATION"));
        assertEquals(0L, effects(s, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(0L, effects(s, "EXTERNAL_COMPENSATION"));
    }

    private void exhaustByCrashingEveryAttempt(Scenario s) {
        OutboxStore.Claim first = store.claimNext();
        assertNotNull(first);
        assertEquals(s.requestId(), first.settlementRequestId());
        expire(first);
        OutboxStore.Claim second = store.claimNext();
        assertNotNull(second);
        assertEquals(2, second.attemptNumber());
        expire(second);
        assertNull(store.claimNext(), "Retry budget exhausted; no third network request permitted");
        assertEquals("LEASE_EXPIRED", attemptOutcome(s, 1));
        assertEquals("LEASE_EXPIRED", attemptOutcome(s, 2));
        assertEquals("DEAD_LETTER", jobState(s));
        assertEquals("RECONCILIATION_REQUIRED", transferState(s));
        assertUnresolvedWithoutRefund(s);
    }

    @Test
    void crashAfterClaimBeforeHttpIsReclaimedWithNoDoubleReservation() {
        Scenario s = reserve();
        OutboxStore.Claim crashed = store.claimNext();
        assertNotNull(crashed);
        assertEquals("IN_FLIGHT", attemptOutcome(s, 1));
        assertNull(store.claimNext(), "Active lease must prevent another worker from claiming");
        verifyNoInteractions(transport);

        expire(crashed);
        when(transport.submit(any())).thenReturn(settled(s));
        OutboxProcessor restartedWorker = new OutboxProcessor(store, transport,meterRegistry);
        assertTrue(restartedWorker.processOne());

        assertEquals("LEASE_EXPIRED", attemptOutcome(s, 1));
        assertEquals("SETTLED", attemptOutcome(s, 2));
        assertEquals("PROCESSED", jobState(s));
        assertEquals("SETTLED", transferState(s));
        assertEquals(1L, effects(s, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(0L, effects(s, "EXTERNAL_COMPENSATION"));
        assertEquals(80_000L, customerBalance(s));
        verify(transport).submit(argThat(command -> command.settlementRequestId().equals(s.requestId())));
    }

    @Test
    void remoteSuccessThenCrashBeforeLocalCommitIsRecoveredUsingSameId() {
        Scenario s = reserve();
        Map<UUID, SettlementTransport.Receipt> externalRecords = new ConcurrentHashMap<>();
        when(transport.submit(any())).thenAnswer(call -> {
            SettlementTransport.Command command = call.getArgument(0);
            return externalRecords.computeIfAbsent(command.settlementRequestId(), ignored ->
                    new SettlementTransport.Receipt(command.settlementRequestId(), command.amountMinor(),
                            command.destinationReference(), "SETTLED", "SIM-" + command.settlementRequestId()));
        });
        OutboxStore.Claim crashed = store.claimNext();
        assertNotNull(crashed);
        // External participant committed; simulated JVM crash means no store.complete() call.
        SettlementTransport.Receipt original = transport.submit(new SettlementTransport.Command(
                crashed.settlementRequestId(), crashed.amountMinor(), crashed.destinationReference(), crashed.mode()));
        assertEquals("SETTLED", original.status());
        assertEquals("IN_FLIGHT", attemptOutcome(s, 1));
        assertUnresolvedWithoutRefund(s);

        expire(crashed);
        assertTrue(new OutboxProcessor(store, transport, meterRegistry).processOne());
        assertEquals(1, externalRecords.size(), "External command is idempotent by settlement request ID");
        verify(transport, times(2)).submit(argThat(command -> command.settlementRequestId().equals(s.requestId())));
        assertEquals("LEASE_EXPIRED", attemptOutcome(s, 1));
        assertEquals("SETTLED", attemptOutcome(s, 2));
        assertEquals("SETTLED", transferState(s));
        assertEquals(1L, effects(s, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(80_000L, customerBalance(s));
        // A resurrected/stale worker is not allowed to post a second ledger effect.
        assertThrows(IllegalStateException.class, () -> store.complete(crashed,
                new OutboxStore.Completion("SETTLED", 200, null,
                        "SIM-" + s.requestId(), null, true, false, 10L)));
        assertEquals(1L, effects(s, "EXTERNAL_SETTLEMENT_FINALIZATION"));
    }

    @Test
    void staleWorkerCannotOverrideReplacementWorkersVerifiedRejection() {
        Scenario s = reserve();
        OutboxStore.Claim stale = store.claimNext();
        assertNotNull(stale);
        expire(stale);
        OutboxStore.Claim current = store.claimNext();
        assertNotNull(current);
        assertEquals("LEASE_EXPIRED", attemptOutcome(s, 1));

        assertThrows(IllegalStateException.class, () -> store.complete(stale,
                new OutboxStore.Completion("SETTLED", 200, null,
                        "SIM-" + s.requestId(), null, true, false, 3L)));
        assertEquals("PROCESSING", jobState(s));
        assertEquals("PENDING_SETTLEMENT", transferState(s));
        assertUnresolvedWithoutRefund(s);

        // A trusted REJECTED receipt has been checked by the worker boundary.
        store.complete(current, new OutboxStore.Completion("REJECTED", 200,
                null, null, null, true, false, 4L));
        assertEquals("FAILED", transferState(s));
        assertEquals("PROCESSED", jobState(s));
        assertEquals("REJECTED", attemptOutcome(s, 2));
        assertEquals(1L, effects(s, "EXTERNAL_COMPENSATION"));
        assertEquals(0L, effects(s, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(100_000L, customerBalance(s));
    }

    @Test
    void retryAfterTimeoutSurvivesReplacementWorkerAndPreservesRequestIdentity() {
        Scenario s = reserve();
        when(transport.submit(any()))
                .thenThrow(new ResourceAccessException("response lost"))
                .thenReturn(settled(s));
        when(transport.get(s.requestId()))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
        assertTrue(processor.processOne());
        assertEquals("NOT_FOUND", attemptOutcome(s, 1));
        assertEquals("PENDING", jobState(s));
        assertEquals("PENDING_SETTLEMENT", transferState(s));
        assertUnresolvedWithoutRefund(s);

        jdbc.update("UPDATE outbox_events SET available_at='-infinity'::timestamptz WHERE aggregate_id=?",
                s.transferId());
        assertTrue(new OutboxProcessor(store, transport, meterRegistry).processOne());
        assertEquals("SETTLED", attemptOutcome(s, 2));
        assertEquals("PROCESSED", jobState(s));
        assertEquals("SETTLED", transferState(s));
        assertEquals(1L, effects(s, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(80_000L, customerBalance(s));
        verify(transport, times(2)).submit(argThat(command -> command.settlementRequestId().equals(s.requestId())));
    }

    @Test
    void expiredLastLeaseBecomesDeadLetterAndAuditedUncertaintyNotFailure() {
        Scenario s = reserve();
        exhaustByCrashingEveryAttempt(s);
        verifyNoInteractions(transport);
        assertEquals(0L, effects(s, "EXTERNAL_COMPENSATION"));
        assertEquals(0L, effects(s, "EXTERNAL_SETTLEMENT_FINALIZATION"));
    }

    @Test
    void deadLetterAfterCrashesCanBeResolvedByOpsWithFreshExternalProof() {
        Scenario s = reserve();
        exhaustByCrashingEveryAttempt(s);
        when(transport.get(s.requestId())).thenReturn(settled(s));

        ReconciliationService.RunView run = reconciliation.run("ops.m14");
        ReconciliationService.IssueView issue = reconciliation.issues(run.id()).stream()
                .filter(it -> it.resourceId().equals(s.transferId())
                        && "EXTERNAL_SETTLED_INTERNAL_PENDING".equals(it.issueType()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing recovery issue"));
        assertEquals("OPEN", issue.status());
        assertUnresolvedWithoutRefund(s);

        var result = resolution.resolve(issue.id(), "ops.m14");
        assertEquals("FINALIZED_VERIFIED_SETTLEMENT", result.disposition());
        assertEquals("SETTLED", transferState(s));
        assertEquals("PROCESSED", jobState(s));
        assertEquals(1L, effects(s, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(0L, effects(s, "EXTERNAL_COMPENSATION"));
        assertEquals(80_000L, customerBalance(s));
        assertEquals("RESOLVED", reconciliation.issues(run.id()).stream()
                .filter(it -> it.id().equals(issue.id())).findFirst().orElseThrow().status());
        assertEquals("ALREADY_RESOLVED", resolution.resolve(issue.id(), "ops.m14").disposition());
        assertEquals(1L, effects(s, "EXTERNAL_SETTLEMENT_FINALIZATION"));
    }

    @Test
    void unknownRemoteOutcomeAfterLastCrashRemainsUnresolvedAndUncompensated() {
        Scenario s = reserve();
        exhaustByCrashingEveryAttempt(s);
        when(transport.get(s.requestId()))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        ReconciliationService.RunView run = reconciliation.run("ops.m14");
        assertTrue(reconciliation.issues(run.id()).stream()
                .anyMatch(it -> it.resourceId().equals(s.transferId())
                        && "UNKNOWN_SETTLEMENT_STATE".equals(it.issueType())));
        assertEquals("RECONCILIATION_REQUIRED", transferState(s));
        assertEquals("DEAD_LETTER", jobState(s));
        assertUnresolvedWithoutRefund(s);
    }
}
