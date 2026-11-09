package com.payrail.backend;

import com.payrail.backend.account.Account;
import com.payrail.backend.account.AccountRepository;
import com.payrail.backend.ledger.InitialFundingService;
import com.payrail.backend.outbox.OutboxStore;
import com.payrail.backend.settlement.SettlementCallbackAuthenticator;
import com.payrail.backend.settlement.SettlementCallbackEvent;
import com.payrail.backend.transfer.ExternalReservationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL + MockMvc: HMAC, event deduplication and exactly one terminal ledger effect. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "PAYRAIL_CALLBACK_HMAC_SECRET=test-only-shared-secret-not-used-in-production",
        "payrail.outbox.worker.enabled=false"
})
class M12CallbackIntegrationTest {
    private static final String SECRET = "test-only-shared-secret-not-used-in-production";
    private static final String ENDPOINT = "/api/settlement/callbacks";

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accounts;
    @Autowired InitialFundingService funding;
    @Autowired ExternalReservationService reservations;
    @Autowired OutboxStore outbox;
    @Autowired JdbcTemplate jdbc;

    private UUID testTransferId;
    private record Fixture(UUID customerId, UUID transferId, UUID requestId) { }

    @AfterEach
    void cleanupUnprocessedJob() {
        if (testTransferId != null) {
            jdbc.update("""
                    UPDATE outbox_events SET status = 'DEAD_LETTER', locked_until = NULL, locked_by = NULL
                    WHERE aggregate_id = ? AND status IN ('PENDING', 'PROCESSING')
                    """, testTransferId);
        }
        testTransferId = null;
    }

    private Fixture reserve() {
        Account customer = Account.customer("M12-CUST-" + UUID.randomUUID(), UUID.randomUUID());
        Account source = Account.system("M12-FUND-" + UUID.randomUUID());
        accounts.saveAllAndFlush(List.of(customer, source));
        funding.fund(customer.getId(), source.getId(), 100_000L);
        var reservation = reservations.reserve(customer.getId(), "BANK-M12", 20_000L,
                UUID.randomUUID().toString());
        testTransferId = reservation.transferId();
        jdbc.update("UPDATE outbox_events SET available_at = '-infinity'::timestamptz WHERE aggregate_id = ?",
                testTransferId);
        return new Fixture(customer.getId(), reservation.transferId(), reservation.settlementRequestId());
    }

    private SettlementCallbackEvent event(Fixture f, UUID eventId, String status) {
        return new SettlementCallbackEvent(eventId, f.requestId(), 20_000L,
                "BANK-M12", status, "SETTLED".equals(status) ? "SIM-" + f.requestId() : null);
    }

    private String json(SettlementCallbackEvent e) {
        return """
                {"eventId":"%s","settlementRequestId":"%s","amountMinor":%d,
                 "destinationReference":"%s","status":"%s","externalReference":%s}
                """.formatted(e.eventId(), e.settlementRequestId(), e.amountMinor(),
                e.destinationReference(), e.status(),
                e.externalReference() == null ? "null" : "\"" + e.externalReference() + "\"");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signed(
            SettlementCallbackEvent event) {
        return post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                .header("X-PayRail-Signature", SettlementCallbackAuthenticator.signature(event, SECRET))
                .content(json(event));
    }

    private String transferState(Fixture f) {
        return jdbc.queryForObject("SELECT status FROM transfers WHERE id = ?", String.class, f.transferId());
    }

    private String jobState(Fixture f) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE aggregate_id = ?",
                String.class, f.transferId());
    }

    private long balance(Fixture f) {
        return jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?",
                Long.class, f.customerId());
    }

    private long effects(Fixture f, String type) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM ledger_transactions WHERE transfer_id = ? AND effect_type = ?
                """, Long.class, f.transferId(), type);
    }

    private long callbackCount(Fixture f) {
        return jdbc.queryForObject("SELECT count(*) FROM settlement_callbacks WHERE transfer_id = ?",
                Long.class, f.transferId());
    }

    @Test
    void canonicalHmacMatchesTheSimulatorWireContract() {
        SettlementCallbackEvent sample = new SettlementCallbackEvent(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                2500L, "BANK-001", "SETTLED", "SIM-123");
        assertEquals("sha256=bb03a2718aa090763be55251bb8ae23bac575eb58e4b6515c39afb3171010d6d",
                SettlementCallbackAuthenticator.signature(sample, SECRET));
    }

    @Test
    void signedSettlementFinalizesExactlyOnceWithoutSecondDebit() throws Exception {
        Fixture f = reserve();
        mvc.perform(signed(event(f, UUID.randomUUID(), "SETTLED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("FINALIZED"));
        assertEquals("PROCESSED", jobState(f));
        assertEquals("SETTLED", transferState(f));
        assertEquals(80_000L, balance(f));
        assertEquals(1L, effects(f, "EXTERNAL_RESERVATION"));
        assertEquals(1L, effects(f, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(0L, effects(f, "EXTERNAL_COMPENSATION"));
        assertEquals(1L, callbackCount(f));
    }

    @Test
    void duplicateEventIdIsAcknowledgedWithoutSecondFinalization() throws Exception {
        Fixture f = reserve();
        SettlementCallbackEvent callback = event(f, UUID.randomUUID(), "SETTLED");
        mvc.perform(signed(callback)).andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("FINALIZED"));
        mvc.perform(signed(callback)).andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("DUPLICATE"));
        assertEquals(1L, callbackCount(f));
        assertEquals(1L, effects(f, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(80_000L, balance(f));
    }

    @Test
    void newEventIdCannotCauseSecondFinancialEffect() throws Exception {
        Fixture f = reserve();
        mvc.perform(signed(event(f, UUID.randomUUID(), "SETTLED")))
                .andExpect(status().isOk());
        mvc.perform(signed(event(f, UUID.randomUUID(), "SETTLED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("ALREADY_FINALIZED"));
        assertEquals(2L, callbackCount(f));
        assertEquals(1L, effects(f, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(0L, effects(f, "EXTERNAL_COMPENSATION"));
    }

    @Test
    void definitiveRejectionCallbackCompensatesOnlyOnce() throws Exception {
        Fixture f = reserve();
        SettlementCallbackEvent rejected = event(f, UUID.randomUUID(), "REJECTED");
        mvc.perform(signed(rejected)).andExpect(status().isOk());
        mvc.perform(signed(rejected)).andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("DUPLICATE"));
        assertEquals("FAILED", transferState(f));
        assertEquals(100_000L, balance(f));
        assertEquals(1L, effects(f, "EXTERNAL_COMPENSATION"));
        assertEquals(0L, effects(f, "EXTERNAL_SETTLEMENT_FINALIZATION"));
    }

    @Test
    void missingOrInvalidSignatureCannotChangeFinancialState() throws Exception {
        Fixture f = reserve();
        SettlementCallbackEvent callback = event(f, UUID.randomUUID(), "SETTLED");
        mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(json(callback)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                        .header("X-PayRail-Signature", "sha256=0000")
                        .content(json(callback)))
                .andExpect(status().isUnauthorized());
        assertEquals("PENDING_SETTLEMENT", transferState(f));
        assertEquals(0L, callbackCount(f));
        assertEquals(0L, effects(f, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(80_000L, balance(f));
    }

    @Test
    void reusingEventIdWithDifferentPayloadIsConflict() throws Exception {
        Fixture f = reserve();
        UUID eventId = UUID.randomUUID();
        mvc.perform(signed(event(f, eventId, "SETTLED"))).andExpect(status().isOk());
        mvc.perform(signed(event(f, eventId, "REJECTED"))).andExpect(status().isConflict());
        assertEquals("SETTLED", transferState(f));
        assertEquals(1L, callbackCount(f));
        assertEquals(0L, effects(f, "EXTERNAL_COMPENSATION"));
    }

    @Test
    void mismatchedAmountCannotFinalizeEvenWithValidSignature() throws Exception {
        Fixture f = reserve();
        SettlementCallbackEvent fake = new SettlementCallbackEvent(UUID.randomUUID(), f.requestId(),
                21_000L, "BANK-M12", "SETTLED", "SIM-OTHER");
        mvc.perform(signed(fake)).andExpect(status().isConflict());
        assertEquals("PENDING_SETTLEMENT", transferState(f));
        assertEquals(0L, callbackCount(f));
        assertEquals(0L, effects(f, "EXTERNAL_SETTLEMENT_FINALIZATION"));
    }

    @Test
    void callbackCanFinalizeTransferPreviouslyEscalatedForReconciliation() throws Exception {
        Fixture f = reserve();
        jdbc.update("UPDATE transfers SET status = 'RECONCILIATION_REQUIRED' WHERE id = ?", f.transferId());
        jdbc.update("UPDATE outbox_events SET status = 'DEAD_LETTER' WHERE aggregate_id = ?", f.transferId());
        mvc.perform(signed(event(f, UUID.randomUUID(), "SETTLED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("FINALIZED"));
        assertEquals("SETTLED", transferState(f));
        assertEquals("PROCESSED", jobState(f));
        assertEquals(1L, effects(f, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(80_000L, balance(f));
    }

    @Test
    void workerReceiptAfterCallbackMustNotFinalizeTwice() throws Exception {
        Fixture f = reserve();
        OutboxStore.Claim claim = outbox.claimNext();
        assertNotNull(claim);
        assertEquals(f.transferId(), claim.transferId());
        mvc.perform(signed(event(f, UUID.randomUUID(), "SETTLED")))
                .andExpect(status().isOk());
        assertDoesNotThrow(() -> outbox.complete(claim, new OutboxStore.Completion(
                "SETTLED", 200, null, "SIM-" + f.requestId(), null, true, false, 12L)));
        assertEquals("PROCESSED", jobState(f));
        assertEquals("SETTLED", transferState(f));
        assertEquals(1L, effects(f, "EXTERNAL_SETTLEMENT_FINALIZATION"));
        assertEquals(1L, callbackCount(f));
        assertEquals("SETTLED", jdbc.queryForObject(
                "SELECT outcome FROM settlement_attempts WHERE id = ?", String.class, claim.attemptId()));
    }

    @Test
    void contradictingSecondEventDoesNotCompensateAlreadySettledTransfer() throws Exception {
        Fixture f = reserve();
        mvc.perform(signed(event(f, UUID.randomUUID(), "SETTLED"))).andExpect(status().isOk());
        mvc.perform(signed(event(f, UUID.randomUUID(), "REJECTED")))
                .andExpect(status().isConflict());
        assertEquals("SETTLED", transferState(f));
        assertEquals(0L, effects(f, "EXTERNAL_COMPENSATION"));
        assertEquals(1L, callbackCount(f));
    }
}
