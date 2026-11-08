package com.payrail.simulator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
    "simulator.timeout-delay-ms=20",
    "simulator.settlement-delay-ms=150"
})
class SettlementSimulatorIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SettlementService service;

    private SubmitSettlementRequest request(UUID id, long amount, SettlementMode mode) {
        return new SubmitSettlementRequest(id, amount, "EXTERNAL-BANK-ACCOUNT-1", mode);
    }

    private String json(SubmitSettlementRequest request) throws Exception {
        return """
                {"settlementRequestId":"%s","amountMinor":%d,
                 "destinationReference":"%s","mode":"%s"}
                """.formatted(request.settlementRequestId(), request.amountMinor(),
                request.destinationReference(), request.mode().name());
    }

    private long count(UUID id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_requests WHERE settlement_request_id = ?",
                Long.class, id);
    }

    @Test
    void successPersistsAndDuplicateReturnsSameSettlement() throws Exception {
        UUID id = UUID.randomUUID();
        String body = json(request(id, 500L, SettlementMode.SUCCESS));
        mockMvc.perform(post("/settlements").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.externalReference").value("SIM-" + id));
        mockMvc.perform(post("/settlements").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalReference").value("SIM-" + id));
        assertEquals(1L, count(id));
    }

    @Test
    void sameIdDifferentPayloadIsRejected() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(post("/settlements").contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(id, 500L, SettlementMode.SUCCESS))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/settlements").contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(id, 501L, SettlementMode.SUCCESS))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Settlement idempotency conflict"));
        assertEquals(1L, count(id));
    }

    @Test
    void rejectionIsDurable() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(post("/settlements").contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(id, 400L, SettlementMode.REJECTED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        mockMvc.perform(get("/settlements/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertEquals(1L, count(id));
    }

    @Test
    void delayedSuccessUsesPersistedDeadline() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(post("/settlements").contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(id, 400L, SettlementMode.DELAYED_SUCCESS))))
                .andExpect(status().isOk());
        Long deadlineExists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM settlement_requests
                WHERE settlement_request_id = ? AND settle_at IS NOT NULL
                """, Long.class, id);
        assertEquals(1L, deadlineExists);
        Thread.sleep(250);
        mockMvc.perform(get("/settlements/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.externalReference").value("SIM-" + id));
    }

    @Test
    void timeoutBeforeProcessingDoesNotCreateSettlement() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(post("/settlements").contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(id, 100L, SettlementMode.TIMEOUT_BEFORE_PROCESSING))))
                .andExpect(status().isGatewayTimeout());
        mockMvc.perform(get("/settlements/{id}", id))
                .andExpect(status().isNotFound());
        assertEquals(0L, count(id));
    }

    @Test
    void timeoutAfterProcessingPersistsAndCanBeRecovered() throws Exception {
        UUID id = UUID.randomUUID();
        String body = json(request(id, 100L, SettlementMode.TIMEOUT_AFTER_PROCESSING));
        mockMvc.perform(post("/settlements").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isGatewayTimeout());
        assertEquals(1L, count(id));
        mockMvc.perform(get("/settlements/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"));
        mockMvc.perform(post("/settlements").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalReference").value("SIM-" + id));
        assertEquals(1L, count(id));
    }

    @Test
    void concurrentSubmissionsHaveOneDurableEffect() throws Exception {
        UUID id = UUID.randomUUID();
        SubmitSettlementRequest command = request(id, 987L, SettlementMode.SUCCESS);
        int workers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            var futures = IntStream.range(0, workers).mapToObj(i -> pool.submit(() -> {
                start.await();
                return service.submit(command).response().externalReference();
            })).toList();
            start.countDown();
            for (Future<String> future : futures) {
                assertEquals("SIM-" + id, future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1L, count(id));
    }

    @Test
    void invalidAmountReturnsBadRequest() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(post("/settlements").contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(id, 0L, SettlementMode.SUCCESS))))
                .andExpect(status().isBadRequest());
        assertEquals(0L, count(id));
    }
}
