package com.payrail.backend;

import com.payrail.backend.settlement.SettlementTransport;
import com.payrail.backend.settlement.SimulatorSettlementClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Verify the real RestClient JSON wire contract without requiring Docker or modifying the real simulator. */
class SimulatorSettlementClientContractTest {

    @Test
    void sendsStableRequestPayloadAndReadsSimulatorReceipt() throws Exception {
        UUID requestId = UUID.randomUUID();
        AtomicReference<String> posted = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/settlements", exchange -> {
            try {
                posted.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String response = """
                    {"settlementRequestId":"%s", "amountMinor":50000,
                     "destinationReference":"BANK-DEMO-001", "status":"SETTLED",
                     "externalReference":"SIM-%s",
                     "createdAt":"2026-11-08T20:00:00Z", "updatedAt":"2026-11-08T20:00:01Z"}
                    """.formatted(requestId, requestId);
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            SimulatorSettlementClient client = new SimulatorSettlementClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), 1000, 1000);
            var command = new SettlementTransport.Command(requestId, 50000L, "BANK-DEMO-001", "SUCCESS");
            SettlementTransport.Receipt result = client.submit(command);

            assertEquals(requestId, result.settlementRequestId());
            assertEquals("SETTLED", result.status());
            assertEquals("SIM-" + requestId, result.externalReference());
            assertNotNull(result.createdAt());
            assertNotNull(posted.get());
            assertTrue(posted.get().contains("\"settlementRequestId\":\"" + requestId + "\""));
            assertTrue(posted.get().contains("\"amountMinor\":50000"));
            assertTrue(posted.get().contains("\"mode\":\"SUCCESS\""));
        } finally {
            server.stop(0);
        }
    }
}
