
package com.payrail.backend;

import com.payrail.backend.account.AccountRepository;
import org.junit.jupiter.api.Test;
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
class ClientAccountSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    private UUID accountId(String reference) {
        return accountRepository.findByReference(reference)
                .orElseThrow()
                .getId();
    }

    private String authorization(String username, String password) {
        String credentials = username + ":" + password;

        return "Basic " + Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8)
        );
    }

    @Test
    void anonymousCannotReadAccount() throws Exception {
        mockMvc.perform(get("/api/client/accounts/" +
                        accountId("DEMO-CLIENT-ALPHA")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void alphaCanReadOwnAccount() throws Exception {
        mockMvc.perform(get("/api/client/accounts/" +
                        accountId("DEMO-CLIENT-ALPHA"))
                        .header("Authorization",
                                authorization("client.alpha@example.test",
                                        "TestAlpha2026!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference")
                        .value("DEMO-CLIENT-ALPHA"));
    }

    @Test
    void alphaCannotReadBetaAccount() throws Exception {
        mockMvc.perform(get("/api/client/accounts/" +
                        accountId("DEMO-CLIENT-BETA"))
                        .header("Authorization",
                                authorization("client.alpha@example.test",
                                        "TestAlpha2026!")))
                .andExpect(status().isNotFound());
    }

    @Test
    void opsCannotReadClientAccount() throws Exception {
        mockMvc.perform(get("/api/client/accounts/" +
                        accountId("DEMO-CLIENT-ALPHA"))
                        .header("Authorization",
                                authorization("ops@example.test",
                                        "TestOps2026!")))
                .andExpect(status().isForbidden());
    }
}
