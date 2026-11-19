
package com.payrail.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"demo", "public-demo"})
@TestPropertySource(properties = {
        "PAYRAIL_DEMO_CLIENT_ALPHA_PASSWORD=TestAlpha2026!",
        "PAYRAIL_DEMO_CLIENT_BETA_PASSWORD=TestBeta2026!",
        "PAYRAIL_DEMO_OPS_PASSWORD=TestOps2026!"
})
class PublicDemoSecurityIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void publicDemoBlocksOpsEndpoints() throws Exception {
        mvc.perform(get("/api/ops/portal/overview")
                        .with(httpBasic("ops@example.test", "TestOps2026!")))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicDemoBlocksFundingEvenWithCsrf() throws Exception {
        mvc.perform(post("/api/ops/demo-funding")
                        .with(httpBasic("ops@example.test", "TestOps2026!"))
                        .with(csrf())
                        .header("Idempotency-Key",
                                java.util.UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountReference":"DEMO-CLIENT-ALPHA",
                                 "amountMinor":1000}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void clientCanStillReadOwnAccounts() throws Exception {
        mvc.perform(get("/api/client/accounts")
                        .with(httpBasic(
                                "client.alpha@example.test",
                                "TestAlpha2026!"
                        )))
                .andExpect(status().isOk());
    }

    @Test
    void publicDemoBlocksMetricsForOps() throws Exception {
        mvc.perform(get("/actuator/metrics")
                        .with(httpBasic("ops@example.test", "TestOps2026!")))
                .andExpect(status().isForbidden());
    }


@Test
void excessiveSessionRequestsReturn429() throws Exception {
    String ip = "192.0.2.77";

    for (int i = 0; i < 10; i++) {
        mvc.perform(get("/api/ui/session")
                .with(httpBasic(
                        "client.alpha@example.test",
                        "TestAlpha2026!"
                ))
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                }))
            .andExpect(status().isOk());
    }

    mvc.perform(get("/api/ui/session")
            .with(httpBasic(
                    "client.alpha@example.test",
                    "TestAlpha2026!"
            ))
            .with(request -> {
                request.setRemoteAddr(ip);
                return request;
            }))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"));
}


    @Test
    void healthEndpointRemainsPublic() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
