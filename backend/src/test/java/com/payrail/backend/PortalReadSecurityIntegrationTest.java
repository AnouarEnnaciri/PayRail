package com.payrail.backend;

import org.junit.jupiter.api.Test;
import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
class PortalReadSecurityIntegrationTest {
    @Autowired MockMvc mvc;

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
    private static final String CLIENT = basic("client.alpha@example.test", "TestAlpha2026!");
    private static final String OPS = basic("ops@example.test", "TestOps2026!");

    @Test
    void sessionIsAuthenticatedAndSuppliesCsrfForEitherRole() throws Exception {
        mvc.perform(get("/api/ui/session")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ui/session").header("Authorization", CLIENT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("CLIENT"))
                .andExpect(jsonPath("$.csrfToken").isNotEmpty());
        mvc.perform(get("/api/ui/session").header("Authorization", OPS))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("OPS"))
                .andExpect(jsonPath("$.csrfToken").isNotEmpty());
    }

    @Test
    void opsReadEndpointsAreDeniedToClient() throws Exception {
        mvc.perform(get("/api/ops/portal/overview").header("Authorization", CLIENT))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/ops/portal/transfers").header("Authorization", CLIENT))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/ops/portal/overview").header("Authorization", OPS))
                .andExpect(status().isOk()).andExpect(jsonPath("$.transfers").isNumber());
    }

    @Test
    void clientLedgerAndTransferDetailsCannotLeakAnotherAccount() throws Exception {
        mvc.perform(get("/api/client/portal/transfers").header("Authorization", OPS))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/client/portal/transfers").header("Authorization", CLIENT))
                .andExpect(status().isOk());
        mvc.perform(get("/api/client/portal/accounts/" + UUID.randomUUID() + "/ledger")
                        .header("Authorization", CLIENT)).andExpect(status().isNotFound());
        mvc.perform(get("/api/client/portal/transfers/" + UUID.randomUUID())
                        .header("Authorization", CLIENT)).andExpect(status().isNotFound());
        mvc.perform(get("/api/client/portal/beneficiaries").header("Authorization", CLIENT))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].reference").value("DEMO-CLIENT-BETA"));
    }

    @Test
    void readOnlyLedgerAndOpsQueuesLoadAgainstPostgres() throws Exception {
        var accounts = mvc.perform(get("/api/client/accounts").header("Authorization", CLIENT))
                .andExpect(status().isOk()).andReturn();
        String accountId = JsonPath.read(accounts.getResponse().getContentAsString(), "$[0].id");
        mvc.perform(get("/api/client/portal/accounts/" + accountId + "/ledger")
                        .header("Authorization", CLIENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].effectType").isNotEmpty());
        mvc.perform(get("/api/ops/portal/outbox").header("Authorization", OPS))
                .andExpect(status().isOk());
        mvc.perform(get("/api/ops/portal/attempts").header("Authorization", OPS))
                .andExpect(status().isOk());
        mvc.perform(get("/api/ops/portal/callbacks").header("Authorization", OPS))
                .andExpect(status().isOk());
        mvc.perform(get("/api/ops/portal/reconciliation/runs").header("Authorization", OPS))
                .andExpect(status().isOk());
        mvc.perform(get("/api/ops/portal/reconciliation/issues").header("Authorization", OPS))
                .andExpect(status().isOk());
    }

    @Test
    void opsTimelineDoesNotFabricateMissingTransfers() throws Exception {
        mvc.perform(get("/api/ops/portal/timeline/" + UUID.randomUUID())
                .header("Authorization", OPS)).andExpect(status().isNotFound());
    }
}
