package com.payrail.backend;

import com.payrail.backend.account.Account;
import com.payrail.backend.account.AccountRepository;
import com.payrail.backend.ledger.InitialFundingService;
import com.payrail.backend.outbox.OutboxProcessor;
import com.payrail.backend.settlement.SettlementTransport;
import com.payrail.backend.transfer.ExternalReservationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
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
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL/Micrometer/MockMvc proof of M15 observability without changing financial rules. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"payrail.outbox.worker.enabled=false"})
class ObservabilityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AccountRepository accounts;
    @Autowired InitialFundingService funding;
    @Autowired ExternalReservationService reservations;
    @Autowired OutboxProcessor processor;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry registry;
    @MockitoBean SettlementTransport transport;

    private UUID transferToClean;

    private record Fixture(UUID customerId, UUID transferId, UUID requestId) { }

    @AfterEach
    void cleanUpUnfinishedJob() {
        if (transferToClean != null) {
            jdbc.update("""
                    UPDATE outbox_events SET status='DEAD_LETTER', locked_until=NULL, locked_by=NULL
                    WHERE aggregate_id=? AND status IN ('PENDING','PROCESSING')
                    """, transferToClean);
            transferToClean = null;
        }
        MDC.remove("correlation_id");
        MDC.remove("transfer_id");
        MDC.remove("settlement_request_id");
        MDC.remove("outbox_event_id");
    }

    private Fixture reserve() {
        Account sender = Account.customer("M15-CUST-" + UUID.randomUUID(), UUID.randomUUID());
        Account source = Account.system("M15-FUND-" + UUID.randomUUID());
        accounts.saveAllAndFlush(List.of(sender, source));
        funding.fund(sender.getId(), source.getId(), 100_000L);
        var reserved = reservations.reserve(sender.getId(), "BANK-M15", 20_000L,
                UUID.randomUUID().toString());
        transferToClean = reserved.transferId();
        jdbc.update("UPDATE outbox_events SET available_at='-infinity'::timestamptz WHERE aggregate_id=?",
                reserved.transferId());
        return new Fixture(sender.getId(), reserved.transferId(), reserved.settlementRequestId());
    }

    @Test
    void validCorrelationIdIsEchoedAndMdcIsCleanedAfterHttpRequest() throws Exception {
        String id = "m15-request-correl-001";
        mvc.perform(get("/actuator/health").header("X-Correlation-ID", id))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", id));
        assertNull(MDC.get("correlation_id"));
    }

    @Test
    void invalidCorrelationIdIsReplacedWithSafeGeneratedUuid() throws Exception {
        var response = mvc.perform(get("/actuator/health")
                        .header("X-Correlation-ID", "bad id with spaces"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        String id = response.getHeader("X-Correlation-ID");
        assertNotNull(id);
        assertNotEquals("bad id with spaces", id);
        assertDoesNotThrow(() -> UUID.fromString(id));
        assertNull(MDC.get("correlation_id"));
    }

    @Test
    void metricsEndpointRequiresOpsNotClientAndIsHiddenFromAnonymous() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics").with(user("client").roles("CLIENT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics").with(user("ops").roles("OPS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names").isArray());
        mvc.perform(get("/actuator/metrics/payrail.outbox.jobs").with(user("ops").roles("OPS")))
                .andExpect(status().isOk());
    }

    @Test
    void correlationIdCrossesDurableOutboxBoundaryAndOneMetricIsRecorded() {
        String correlation = "m15-origin-connection-001";
        Fixture f;
        MDC.put("correlation_id", correlation);
        try {
            f = reserve();
        } finally {
            MDC.remove("correlation_id");
        }
        String persisted = jdbc.queryForObject("""
                SELECT payload_json ->> 'correlationId' FROM outbox_events WHERE aggregate_id=?
                """, String.class, f.transferId());
        assertEquals(correlation, persisted);
        Counter counter = registry.counter("payrail.settlement.attempts", "outcome", "SETTLED");
        double before = counter.count();

        when(transport.submit(any())).thenAnswer(invocation -> {
            assertEquals(correlation, MDC.get("correlation_id"));
            assertEquals(f.transferId().toString(), MDC.get("transfer_id"));
            assertEquals(f.requestId().toString(), MDC.get("settlement_request_id"));
            SettlementTransport.Command c = invocation.getArgument(0);
            return new SettlementTransport.Receipt(c.settlementRequestId(), c.amountMinor(),
                    c.destinationReference(), "SETTLED", "SIM-" + c.settlementRequestId());
        });
        assertTrue(processor.processOne());
        assertEquals(before + 1.0, counter.count(), 0.000001);
        assertNotNull(registry.find("payrail.settlement.dispatch").timer());
        assertNull(MDC.get("correlation_id"));
        assertNull(MDC.get("transfer_id"));
        assertEquals("SETTLED", jdbc.queryForObject("SELECT status FROM transfers WHERE id=?",
                String.class, f.transferId()));
        assertEquals(1L, jdbc.queryForObject("""
                SELECT count(*) FROM ledger_transactions
                WHERE transfer_id=? AND effect_type='EXTERNAL_SETTLEMENT_FINALIZATION'
                """, Long.class, f.transferId()));
        assertEquals(80_000L, jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id=?",
                Long.class, f.customerId()));
    }

    @Test
    void missingRequestCorrelationGeneratesDurableIdForNonHttpReservation() {
        Fixture f = reserve();
        String persisted = jdbc.queryForObject("""
                SELECT payload_json ->> 'correlationId' FROM outbox_events WHERE aggregate_id=?
                """, String.class, f.transferId());
        assertNotNull(persisted);
        assertDoesNotThrow(() -> UUID.fromString(persisted));
    }

    @Test
    void notFoundIsCountedButNeverMisreportedAsFinancialRejection() {
        Fixture f = reserve();
        Counter counter = registry.counter("payrail.settlement.attempts", "outcome", "NOT_FOUND");
        double before = counter.count();
        when(transport.submit(any())).thenThrow(new ResourceAccessException("lost response"));
        when(transport.get(f.requestId())).thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
        assertTrue(processor.processOne());
        assertEquals(before + 1.0, counter.count(), 0.000001);
        assertEquals("PENDING_SETTLEMENT", jdbc.queryForObject("SELECT status FROM transfers WHERE id=?",
                String.class, f.transferId()));
        assertEquals(0L, jdbc.queryForObject("""
                SELECT count(*) FROM ledger_transactions
                WHERE transfer_id=? AND effect_type IN ('EXTERNAL_SETTLEMENT_FINALIZATION','EXTERNAL_COMPENSATION')
                """, Long.class, f.transferId()));
    }

    @Test
    void gaugesExposeFixedStatesWithoutTransferIdsAsTags() {
        var pending = registry.find("payrail.outbox.jobs").tag("status", "PENDING").gauge();
        var dead = registry.find("payrail.outbox.jobs").tag("status", "DEAD_LETTER").gauge();
        var openIssues = registry.find("payrail.reconciliation.issues")
                .tag("status", "OPEN").gauge();
        assertNotNull(pending);
        assertNotNull(dead);
        assertNotNull(openIssues);
        assertTrue(pending.value() >= 0);
        assertTrue(dead.value() >= 0);
        assertTrue(openIssues.value() >= 0);
        assertTrue(registry.getMeters().stream()
                .filter(m -> m.getId().getName().startsWith("payrail."))
                .flatMap(m -> m.getId().getTags().stream())
                .noneMatch(tag -> tag.getKey().contains("id") || tag.getKey().contains("reference")));
    }
}
