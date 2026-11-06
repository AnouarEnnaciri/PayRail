
package com.payrail.backend;


import com.payrail.backend.account.Account;
import com.payrail.backend.ledger.InitialFundingService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import com.payrail.backend.account.AccountRepository;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("demo")
@TestPropertySource(properties = {
        "PAYRAIL_DEMO_CLIENT_ALPHA_PASSWORD=TestAlpha2026!",
        "PAYRAIL_DEMO_CLIENT_BETA_PASSWORD=TestBeta2026!",
        "PAYRAIL_DEMO_OPS_PASSWORD=TestOps2026!"
})
class ClientTransferSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private InitialFundingService initialFundingService;

    private UUID accountId(String reference) {
        return accountRepository.findByReference(reference)
                .orElseThrow()
                .getId();
    }

    private String alphaAuthorization() {
        String credentials =
                "client.alpha@example.test:TestAlpha2026!";

        return "Basic " + Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8)
        );
    }



    private String transferBody(
            UUID senderId,
            UUID receiverId,
            long amountMinor
    ) {
        return """
            {
                "senderAccountId": "%s",
                "receiverAccountId": "%s",
                "amountMinor": %d
            }
            """.formatted(senderId, receiverId, amountMinor);
    }

    private String transferBody(UUID senderId, UUID receiverId) {
        return transferBody(senderId, receiverId, 1000L);
    }


    @Test
    void alphaCannotDebitBetaAccount() throws Exception {
        UUID alphaId = accountId("DEMO-CLIENT-ALPHA");
        UUID betaId = accountId("DEMO-CLIENT-BETA");

        mockMvc.perform(post("/api/client/transfers")
                        .with(csrf())
                        .header("Authorization", alphaAuthorization())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(betaId, alphaId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void postWithoutCsrfIsForbidden() throws Exception {
        UUID alphaId = accountId("DEMO-CLIENT-ALPHA");
        UUID betaId = accountId("DEMO-CLIENT-BETA");

        mockMvc.perform(post("/api/client/transfers")
                        .header("Authorization", alphaAuthorization())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(alphaId, betaId)))
                .andExpect(status().isForbidden());
    }


    @Test
    void alphaCanTransferMoneyToBeta() throws Exception {
        UUID alphaId = accountId("DEMO-CLIENT-ALPHA");
        UUID betaId = accountId("DEMO-CLIENT-BETA");

        Account system = accountRepository.saveAndFlush(
                Account.system("TEST-FUNDING-" + UUID.randomUUID())
        );

        // Give Alpha 100 MAD.
        initialFundingService.fund(
                alphaId, system.getId(), 10_000L
        );

        long alphaBefore = accountRepository.findById(alphaId)
                .orElseThrow().getBalanceMinor();

        long betaBefore = accountRepository.findById(betaId)
                .orElseThrow().getBalanceMinor();

        // Transfer 10 MAD from Alpha to Beta.
        mockMvc.perform(post("/api/client/transfers")
                        .with(csrf())
                        .header("Authorization", alphaAuthorization())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(alphaId, betaId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").exists());

        long alphaAfter = accountRepository.findById(alphaId)
                .orElseThrow().getBalanceMinor();

        long betaAfter = accountRepository.findById(betaId)
                .orElseThrow().getBalanceMinor();

        assertEquals(alphaBefore - 1_000L, alphaAfter);
        assertEquals(betaBefore + 1_000L, betaAfter);
    }


    @Test
    void repeatingSameTransferDoesNotDebitTwice() throws Exception {
        UUID alphaId = accountId("DEMO-CLIENT-ALPHA");
        UUID betaId = accountId("DEMO-CLIENT-BETA");

        Account system = accountRepository.saveAndFlush(
                Account.system("TEST-FUNDING-" + UUID.randomUUID())
        );

        initialFundingService.fund(
                alphaId, system.getId(), 10_000L
        );

        long alphaBefore = accountRepository.findById(alphaId)
                .orElseThrow().getBalanceMinor();

        long betaBefore = accountRepository.findById(betaId)
                .orElseThrow().getBalanceMinor();

        String key = UUID.randomUUID().toString();
        String body = transferBody(alphaId, betaId);

        String firstResponse = mockMvc.perform(
                        post("/api/client/transfers")
                                .with(csrf())
                                .header("Authorization", alphaAuthorization())
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String secondResponse = mockMvc.perform(
                        post("/api/client/transfers")
                                .with(csrf())
                                .header("Authorization", alphaAuthorization())
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(firstResponse, secondResponse);

        assertEquals(alphaBefore - 1_000L,
                accountRepository.findById(alphaId)
                        .orElseThrow().getBalanceMinor());

        assertEquals(betaBefore + 1_000L,
                accountRepository.findById(betaId)
                        .orElseThrow().getBalanceMinor());
    }


    @Test
    void differentTransferWithSameKeyReturnsConflict() throws Exception {
        UUID alphaId = accountId("DEMO-CLIENT-ALPHA");
        UUID betaId = accountId("DEMO-CLIENT-BETA");

        Account system = accountRepository.saveAndFlush(
                Account.system("TEST-FUNDING-" + UUID.randomUUID())
        );

        initialFundingService.fund(
                alphaId, system.getId(), 10_000L
        );

        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/client/transfers")
                        .with(csrf())
                        .header("Authorization", alphaAuthorization())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(alphaId, betaId, 1000L)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/client/transfers")
                        .with(csrf())
                        .header("Authorization", alphaAuthorization())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(alphaId, betaId, 2000L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title")
                        .value("Idempotency conflict"));
    }

    @Test
    void insufficientFundsReturnsConflict() throws Exception {
        UUID alphaId = accountId("DEMO-CLIENT-ALPHA");
        UUID betaId = accountId("DEMO-CLIENT-BETA");

        long balanceBefore = accountRepository.findById(alphaId)
                .orElseThrow().getBalanceMinor();

        long betaBefore = accountRepository.findById(betaId)
                .orElseThrow().getBalanceMinor();

        mockMvc.perform(post("/api/client/transfers")
                        .with(csrf())
                        .header("Authorization", alphaAuthorization())
                        .header("Idempotency-Key",
                                UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(
                                alphaId, betaId, Long.MAX_VALUE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title")
                        .value("Insufficient funds"));

        assertEquals(balanceBefore,
                accountRepository.findById(alphaId)
                        .orElseThrow().getBalanceMinor());

        assertEquals(betaBefore,
                accountRepository.findById(betaId)
                        .orElseThrow().getBalanceMinor());
    }


}
