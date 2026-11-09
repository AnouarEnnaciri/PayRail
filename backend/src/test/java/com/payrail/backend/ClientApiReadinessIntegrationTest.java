package com.payrail.backend;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class ClientApiReadinessIntegrationTest {

    @Autowired MockMvc mvc;

    private String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private String alpha() {
        return basic("client.alpha@example.test", "TestAlpha2026!");
    }

    @Test
    void accountListIsScopedToAuthenticatedOwner() throws Exception {
        mvc.perform(get("/api/client/accounts"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/client/accounts").header("Authorization", alpha()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reference").value("DEMO-CLIENT-ALPHA"))
                .andExpect(jsonPath("$[1]").doesNotExist());

        mvc.perform(get("/api/client/accounts")
                        .header("Authorization", basic("ops@example.test", "TestOps2026!")))
                .andExpect(status().isForbidden());
    }

    @Test
    void issuedCsrfTokenEnablesPostWithoutDisablingCsrfProtection() throws Exception {
        mvc.perform(get("/api/client/csrf"))
                .andExpect(status().isUnauthorized());

        MvcResult issued = mvc.perform(get("/api/client/csrf")
                        .header("Authorization", alpha()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();

        String token = JsonPath.read(issued.getResponse().getContentAsString(), "$.token");
        String headerName = JsonPath.read(issued.getResponse().getContentAsString(), "$.headerName");
        MockHttpSession session = (MockHttpSession) issued.getRequest().getSession(false);
        assertNotNull(session, "The CSRF token must be tied to an HTTP session");

        // Invalid business input is deliberately used: 400 proves CSRF passed, without moving money.
        mvc.perform(post("/api/client/transfers")
                        .session(session)
                        .header("Authorization", alpha())
                        .header(headerName, token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"senderAccountId\":\"" + UUID.randomUUID()
                                + "\",\"receiverAccountId\":\"" + UUID.randomUUID()
                                + "\",\"amountMinor\":0}"))
                .andExpect(status().isBadRequest());
    }
}
