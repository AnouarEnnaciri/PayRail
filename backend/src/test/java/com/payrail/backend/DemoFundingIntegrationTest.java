package com.payrail.backend;

import com.payrail.backend.account.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class DemoFundingIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountRepository accounts;

    private long balance() {
        return accounts.findByReference("DEMO-CLIENT-ALPHA").orElseThrow().getBalanceMinor();
    }
    private String body(long minor) {
        return "{\"accountReference\":\"DEMO-CLIENT-ALPHA\",\"amountMinor\":" + minor + "}";
    }
    private org.springframework.test.web.servlet.ResultActions fund(String key, long minor) throws Exception {
        return mvc.perform(post("/api/ops/demo-funding")
                .with(httpBasic("ops@example.test", "TestOps2026!"))
                .with(csrf()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body(minor)));
    }

    @Test
    void fundingIsBalancedAndIdempotent() throws Exception {
        long before = balance();
        String key = UUID.randomUUID().toString();
        fund(key, 1200L).andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));
        fund(key, 1200L).andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true));
        assertEquals(before + 1200L, balance());
        String ref = "DEMO-TOPUP:" + key;
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions WHERE funding_reference=?", Long.class, ref);
        Long entries = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ledger_entries e JOIN ledger_transactions t
                ON t.id=e.ledger_transaction_id WHERE t.funding_reference=?
                """, Long.class, ref);
        Long sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(e.signed_amount_minor),0) FROM ledger_entries e
                JOIN ledger_transactions t ON t.id=e.ledger_transaction_id WHERE t.funding_reference=?
                """, Long.class, ref);
        assertEquals(1L, count);
        assertEquals(2L, entries);
        assertEquals(0L, sum);
    }

    @Test
    void differentRequestWithSameKeyIsRejected() throws Exception {
        String key = UUID.randomUUID().toString();
        fund(key, 500L).andExpect(status().isCreated());
        long after = balance();
        fund(key, 1000L).andExpect(status().isConflict());
        assertEquals(after, balance());
    }

    @Test
    void clientCannotFundAndCsrfIsRequired() throws Exception {
        String key = UUID.randomUUID().toString();
        mvc.perform(post("/api/ops/demo-funding")
                .with(httpBasic("client.alpha@example.test", "TestAlpha2026!"))
                .with(csrf()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body(500L)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/ops/demo-funding")
                .with(httpBasic("ops@example.test", "TestOps2026!"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body(500L)))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidAmountAndUnknownAccountAreRejected() throws Exception {
        fund(UUID.randomUUID().toString(), 0L).andExpect(status().isBadRequest());
        mvc.perform(post("/api/ops/demo-funding")
                .with(httpBasic("ops@example.test", "TestOps2026!"))
                .with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountReference\":\"OTHER\",\"amountMinor\":100}"))
                .andExpect(status().isBadRequest());
    }
}
