package com.payrail.backend;

import com.payrail.backend.account.Account;
import com.payrail.backend.account.AccountRepository;
import com.payrail.backend.ledger.InitialFundingService;
import com.payrail.backend.reconciliation.ReconciliationResolutionService;
import com.payrail.backend.reconciliation.ReconciliationService;
import com.payrail.backend.settlement.SettlementTransport;
import com.payrail.backend.transfer.ExternalReservationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL/Testcontainers: detection is read-only; OPS recovery needs live external evidence. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "payrail.outbox.worker.enabled=false",
        "payrail.reconciliation.max-external-checks=10000"
})
class FinancialReconciliationIntegrationTest {
    @Autowired AccountRepository accounts;
    @Autowired InitialFundingService funding;
    @Autowired ExternalReservationService reservations;
    @Autowired ReconciliationService reconciliation;
    @Autowired ReconciliationResolutionService resolution;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean SettlementTransport transport;

    private UUID transferToClean;
    private record Fixture(UUID customerId, UUID transferId, UUID requestId) { }

    @AfterEach
    void cleanupMyPendingJob() {
        if (transferToClean != null) {
            jdbc.update("""
                    UPDATE outbox_events SET status='DEAD_LETTER', locked_until=NULL, locked_by=NULL
                    WHERE aggregate_id=? AND status IN ('PENDING','PROCESSING')
                    """, transferToClean);
            transferToClean = null;
        }
    }

    private Fixture reserve() {
        Account customer = Account.customer("M13-CUST-" + UUID.randomUUID(), UUID.randomUUID());
        Account fundingSource = Account.system("M13-FUND-" + UUID.randomUUID());
        accounts.saveAllAndFlush(List.of(customer, fundingSource));
        funding.fund(customer.getId(), fundingSource.getId(), 100_000L);
        var reserved = reservations.reserve(customer.getId(), "BANK-M13", 20_000L,
                UUID.randomUUID().toString());
        transferToClean = reserved.transferId();
        return new Fixture(customer.getId(), reserved.transferId(), reserved.settlementRequestId());
    }

    private SettlementTransport.Receipt receipt(Fixture f, String status) {
        return new SettlementTransport.Receipt(f.requestId(), 20_000L, "BANK-M13", status,
                "SETTLED".equals(status) ? "SIM-" + f.requestId() : null);
    }

    private long balance(Fixture f) {
        return jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id=?",
                Long.class, f.customerId());
    }

    private String transferStatus(Fixture f) {
        return jdbc.queryForObject("SELECT status FROM transfers WHERE id=?", String.class, f.transferId());
    }

    private long effects(Fixture f, String type) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM ledger_transactions WHERE transfer_id=? AND effect_type=?
                """, Long.class, f.transferId(), type);
    }

    private ReconciliationService.IssueView issue(ReconciliationService.RunView run,
                                                  UUID resourceId, String type) {
        return reconciliation.issues(run.id()).stream()
                .filter(it -> it.resourceId().equals(resourceId) && it.issueType().equals(type))
                .findFirst().orElseThrow(() -> new AssertionError("Missing " + type + " issue for " + resourceId));
    }

    @Test
    void verifiesExternalSettlementAndFinalizesOnceWithAnAuditedIssue() {
        Fixture f = reserve();
        when(transport.get(f.requestId())).thenReturn(receipt(f, "SETTLED"));

        ReconciliationService.RunView run = reconciliation.run("ops.test");
        ReconciliationService.IssueView found = issue(run, f.transferId(), "EXTERNAL_SETTLED_INTERNAL_PENDING");
        assertEquals("COMPLETED", reconciliation.getRun(run.id()).status());
        assertEquals("OPEN", found.status());
        assertEquals("PENDING_SETTLEMENT", transferStatus(f), "Detection must not change financial state");
        assertEquals(80_000L, balance(f));
        assertEquals(0L, effects(f, "EXTERNAL_SETTLEMENT_FINALIZATION"));

        var resolved = resolution.resolve(found.id(), "ops.test");
        assertEquals("FINALIZED_VERIFIED_SETTLEMENT", resolved.disposition());
        assertEquals("SETTLED", transferStatus(f));
        assertEquals(80_000L, balance(f));
        assertEquals(1L, effects(f, "EXTERNAL_RESERVATION"));
        assertEquals(1L, effects(f, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(0L, effects(f, "EXTERNAL_COMPENSATION"));
        assertEquals("PROCESSED", jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE aggregate_id=?", String.class, f.transferId()));
        var audited = issue(run, f.transferId(), "EXTERNAL_SETTLED_INTERNAL_PENDING");
        assertEquals("RESOLVED", audited.status());
        assertEquals("ops.test", audited.resolutionActor());
        assertNotNull(audited.evidenceReference());

        assertEquals("ALREADY_RESOLVED", resolution.resolve(found.id(), "ops.test").disposition());
        assertEquals(1L, effects(f, "EXTERNAL_SETTLEMENT_FINALIZATION"));
    }

    @Test
    void verifiedRejectionCompensatesOnceOnlyAfterExplicitOpsResolution() {
        Fixture f = reserve();
        when(transport.get(f.requestId())).thenReturn(receipt(f, "REJECTED"));
        var run = reconciliation.run("ops.test");
        var found = issue(run, f.transferId(), "EXTERNAL_REJECTED_INTERNAL_PENDING");
        assertEquals(80_000L, balance(f));
        assertEquals(0L, effects(f, "EXTERNAL_COMPENSATION"));

        assertEquals("COMPENSATED_VERIFIED_REJECTION", resolution.resolve(found.id(), "ops.test").disposition());
        assertEquals("FAILED", transferStatus(f));
        assertEquals(100_000L, balance(f));
        assertEquals(1L, effects(f, "EXTERNAL_COMPENSATION"));
        assertEquals(0L, effects(f, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals("ALREADY_RESOLVED", resolution.resolve(found.id(), "ops.test").disposition());
        assertEquals(1L, effects(f, "EXTERNAL_COMPENSATION"));
    }

    @Test
    void aNotFoundAtResolutionTimeCannotAuthorizeSettlementOrRefund() {
        Fixture f = reserve();
        when(transport.get(f.requestId())).thenReturn(receipt(f, "SETTLED"));
        var found = issue(reconciliation.run("ops.test"), f.transferId(), "EXTERNAL_SETTLED_INTERNAL_PENDING");
        when(transport.get(f.requestId())).thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> resolution.resolve(found.id(), "ops.test"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("PENDING_SETTLEMENT", transferStatus(f));
        assertEquals("OPEN", jdbc.queryForObject(
                "SELECT status FROM reconciliation_issues WHERE id=?", String.class, found.id()));
        assertEquals(0L, effects(f, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(0L, effects(f, "EXTERNAL_COMPENSATION"));
        assertEquals(80_000L, balance(f));
    }

    @Test
    void mismatchedAmountAtResolutionTimeCannotCauseAnyFinancialEffect() {
        Fixture f = reserve();
        when(transport.get(f.requestId())).thenReturn(receipt(f, "SETTLED"));
        var found = issue(reconciliation.run("ops.test"), f.transferId(), "EXTERNAL_SETTLED_INTERNAL_PENDING");
        when(transport.get(f.requestId())).thenReturn(new SettlementTransport.Receipt(
                f.requestId(), 21_000L, "BANK-M13", "SETTLED", "SIM-INVALID"));

        assertThrows(ResponseStatusException.class, () -> resolution.resolve(found.id(), "ops.test"));
        assertEquals("PENDING_SETTLEMENT", transferStatus(f));
        assertEquals(0L, effects(f, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(0L, effects(f, "EXTERNAL_COMPENSATION"));
    }

    @Test
    void reconciledDeadLetterTransferCanSettleAfterFreshEvidence() {
        Fixture f = reserve();
        jdbc.update("UPDATE transfers SET status='RECONCILIATION_REQUIRED' WHERE id=?", f.transferId());
        jdbc.update("UPDATE outbox_events SET status='DEAD_LETTER' WHERE aggregate_id=?", f.transferId());
        when(transport.get(f.requestId())).thenReturn(receipt(f, "SETTLED"));
        var found = issue(reconciliation.run("ops.test"), f.transferId(),
                "EXTERNAL_SETTLED_INTERNAL_PENDING");
        assertEquals("RECONCILIATION_REQUIRED", transferStatus(f));
        resolution.resolve(found.id(), "ops.test");
        assertEquals("SETTLED", transferStatus(f));
        assertEquals("PROCESSED", jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE aggregate_id=?", String.class, f.transferId()));
        assertEquals(1L, effects(f, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(0L, effects(f, "EXTERNAL_COMPENSATION"));
        assertEquals(80_000L, balance(f));
        verify(transport, times(2)).get(f.requestId());
    }

    @Test
    void aCustomerBalanceIssueCannotBeUsedToTriggerFinancialResolution() {
        Fixture f = reserve();
        long initial = balance(f);
        try {
            jdbc.update("UPDATE accounts SET balance_minor=balance_minor+1 WHERE id=?", f.customerId());
            var found = issue(reconciliation.run("ops.test"), f.customerId(),
                    "BALANCE_LEDGER_MISMATCH");
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> resolution.resolve(found.id(), "ops.test"));
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
            assertEquals("OPEN", jdbc.queryForObject(
                    "SELECT status FROM reconciliation_issues WHERE id=?", String.class, found.id()));
            assertEquals(0L, effects(f, "EXTERNAL_COMPENSATION"));
            assertEquals(0L, effects(f, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        } finally {
            jdbc.update("UPDATE accounts SET balance_minor=? WHERE id=?", initial, f.customerId());
        }
    }

    @Test
    void statusLookupNotFoundDoesNotTurnPendingIntoFailed() {
        Fixture f = reserve();
        when(transport.get(f.requestId())).thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
        var run = reconciliation.run("ops.test");
        assertTrue(reconciliation.issues(run.id()).stream()
                .noneMatch(it -> it.resourceId().equals(f.transferId())
                        && "EXTERNAL_REJECTED_INTERNAL_PENDING".equals(it.issueType())));
        assertEquals("PENDING_SETTLEMENT", transferStatus(f));
        assertEquals(0L, effects(f, "EXTERNAL_COMPENSATION"));
    }

    @Test
    void externalStatusCallsAreNotMadeInsideTheDatabaseSnapshotTransaction() {
        Fixture f = reserve();
        when(transport.get(f.requestId())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "Never keep a database transaction open while waiting for external HTTP");
            return receipt(f, "PENDING");
        });
        reconciliation.run("ops.test");
        verify(transport, atLeastOnce()).get(f.requestId());
    }

    @Test
    void aCorruptedCustomerMaterializedBalanceIsDetectedButNotSilentlyRepaired() {
        Fixture f = reserve();
        long initial = balance(f);
        try {
            jdbc.update("UPDATE accounts SET balance_minor=balance_minor+1 WHERE id=?", f.customerId());
            var run = reconciliation.run("ops.test");
            issue(run, f.customerId(), "BALANCE_LEDGER_MISMATCH");
            assertEquals(initial + 1, balance(f), "Detection must not repair the customer balance");
        } finally {
            jdbc.update("UPDATE accounts SET balance_minor=? WHERE id=?", initial, f.customerId());
        }
    }

    @Test
    void anIncompleteLedgerTransactionIsDetectedWithoutModification() {
        Account system = accounts.saveAndFlush(Account.system("M13-IMBAL-" + UUID.randomUUID()));
        UUID tx = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO ledger_transactions (id, effect_type) VALUES (?, 'INITIAL_FUNDING')", tx);
            jdbc.update("""
                    INSERT INTO ledger_entries (id, ledger_transaction_id, account_id, signed_amount_minor, currency)
                    VALUES (?, ?, ?, 50, 'MAD')
                    """, UUID.randomUUID(), tx, system.getId());
            issue(reconciliation.run("ops.test"), tx, "LEDGER_IMBALANCE");
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT count(*) FROM ledger_entries WHERE ledger_transaction_id=?", Long.class, tx));
        } finally {
            // Restore ONLY intentionally corrupted test fixture, never production ledger entries.
            jdbc.update("DELETE FROM ledger_entries WHERE ledger_transaction_id=?", tx);
            jdbc.update("DELETE FROM ledger_transactions WHERE id=?", tx);
        }
    }

    @Test
    void clearingBalanceDiffersFromUnresolvedTransfersWhenOrphanEntryIsInjected() {
        UUID clearingId = jdbc.queryForObject("SELECT id FROM accounts WHERE reference='SETTLEMENT_CLEARING'",
                UUID.class);
        Account offset = accounts.saveAndFlush(Account.system("M13-OFFSET-" + UUID.randomUUID()));
        UUID tx = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO ledger_transactions (id, effect_type) VALUES (?, 'INITIAL_FUNDING')", tx);
            jdbc.update("""
                    INSERT INTO ledger_entries (id, ledger_transaction_id, account_id, signed_amount_minor, currency)
                    VALUES (?, ?, ?, 50, 'MAD')
                    """, UUID.randomUUID(), tx, clearingId);
            jdbc.update("""
                    INSERT INTO ledger_entries (id, ledger_transaction_id, account_id, signed_amount_minor, currency)
                    VALUES (?, ?, ?, -50, 'MAD')
                    """, UUID.randomUUID(), tx, offset.getId());
            issue(reconciliation.run("ops.test"), clearingId, "CLEARING_BALANCE_MISMATCH");
        } finally {
            jdbc.update("DELETE FROM ledger_entries WHERE ledger_transaction_id=?", tx);
            jdbc.update("DELETE FROM ledger_transactions WHERE id=?", tx);
        }
    }

    @Test
    void opsRoleAndCsrfRemainRequiredForRunningReconciliation() throws Exception {
        mvc.perform(post("/api/ops/reconciliation/runs").with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/ops/reconciliation/runs").with(user("client").roles("CLIENT")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/ops/reconciliation/runs").with(user("ops").roles("OPS")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/ops/reconciliation/runs").with(user("ops").roles("OPS")).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists());
        mvc.perform(get("/api/ops/reconciliation/runs/" + UUID.randomUUID())
                        .with(user("client").roles("CLIENT")))
                .andExpect(status().isForbidden());
    }
}
