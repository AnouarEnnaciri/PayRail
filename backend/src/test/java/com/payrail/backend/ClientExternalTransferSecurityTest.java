package com.payrail.backend;

import com.payrail.backend.account.Account;
import com.payrail.backend.account.AccountRepository;
import com.payrail.backend.ledger.InitialFundingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("demo")
@TestPropertySource(properties = {
        "PAYRAIL_DEMO_CLIENT_ALPHA_PASSWORD=TestAlpha2026!",
        "PAYRAIL_DEMO_CLIENT_BETA_PASSWORD=TestBeta2026!",
        "PAYRAIL_DEMO_OPS_PASSWORD=TestOps2026!"
})
class ClientExternalTransferSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired AccountRepository accounts;
    @Autowired InitialFundingService funding;

    private UUID id(String reference) {
        return accounts.findByReference(reference).orElseThrow().getId();
    }

    private String alphaAuth() {
        return "Basic " + Base64.getEncoder().encodeToString(
                "client.alpha@example.test:TestAlpha2026!".getBytes(StandardCharsets.UTF_8));
    }

    private String body(UUID id) {
        return """
                {"senderAccountId":"%s","destinationReference":"BANK-SECURITY-TEST","amountMinor":1000}
                """.formatted(id);
    }

    @Test
    void clientCannotReserveAnotherClientsAccount() throws Exception {
        UUID beta = id("DEMO-CLIENT-BETA");
        mvc.perform(post("/api/client/external-transfers").with(csrf())
                        .header("Authorization", alphaAuth())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body(beta)))
                .andExpect(status().isNotFound());
    }

    @Test
    void successfulClientRequestPersistsPendingReservation() throws Exception {
        UUID alpha = id("DEMO-CLIENT-ALPHA");
        Account system = accounts.saveAndFlush(Account.system("EXTERNAL-API-FUND-" + UUID.randomUUID()));
        funding.fund(alpha, system.getId(), 10_000L);
        long before = accounts.findById(alpha).orElseThrow().getBalanceMinor();
        mvc.perform(post("/api/client/external-transfers").with(csrf())
                        .header("Authorization", alphaAuth())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body(alpha)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").exists())
                .andExpect(jsonPath("$.settlementRequestId").exists())
                .andExpect(jsonPath("$.status").value("PENDING_SETTLEMENT"));
        assertEquals(before - 1000L, accounts.findById(alpha).orElseThrow().getBalanceMinor());
    }

    @Test
    void csrfRequiredOnExternalTransfer() throws Exception {
        mvc.perform(post("/api/client/external-transfers")
                        .header("Authorization", alphaAuth())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body(id("DEMO-CLIENT-ALPHA"))))
                .andExpect(status().isForbidden());
    }
}
