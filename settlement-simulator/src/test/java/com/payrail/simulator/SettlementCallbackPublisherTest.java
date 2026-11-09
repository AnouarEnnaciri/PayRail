package com.payrail.simulator;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SettlementCallbackPublisherTest {
    @Test
    void canonicalHmacMatchesTheBackendWireContract() {
        SettlementCallbackPublisher.Event event = new SettlementCallbackPublisher.Event(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                2500L, "BANK-001", "SETTLED", "SIM-123");
        assertEquals("sha256=bb03a2718aa090763be55251bb8ae23bac575eb58e4b6515c39afb3171010d6d",
                SettlementCallbackPublisher.sign(event, "test-only-shared-secret-not-used-in-production"));
    }

    @Test
    void duplicateModeEmitsSameSignedEventTwiceAfterSettlement() throws Exception {
        AtomicInteger received = new AtomicInteger();
        AtomicReference<String> firstBody = new AtomicReference<>();
        AtomicReference<String> firstSignature = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/api/settlement/callbacks", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String signature = exchange.getRequestHeaders().getFirst("X-PayRail-Signature");
                if (received.getAndIncrement() == 0) {
                    firstBody.set(body);
                    firstSignature.set(signature);
                } else if (!body.equals(firstBody.get()) || !signature.equals(firstSignature.get())) {
                    failure.set(new AssertionError("Duplicate delivery changed event identity"));
                }
                exchange.sendResponseHeaders(200, -1);
            } catch (Throwable ex) {
                failure.set(ex);
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        http.start();
        try {
            String url = "http://127.0.0.1:" + http.getAddress().getPort() + "/api/settlement/callbacks";
            SettlementCallbackPublisher publisher = new SettlementCallbackPublisher(url,
                    "test-only-shared-secret-not-used-in-production");
            UUID requestId = UUID.randomUUID();
            SettlementResponse receipt = new SettlementResponse(requestId, 2500L, "BANK-001",
                    SettlementStatus.SETTLED, "SIM-" + requestId, Instant.now(), Instant.now());
            publisher.publishTwice(receipt);
            assertNull(failure.get());
            assertEquals(2, received.get());
            assertTrue(firstBody.get().contains(requestId.toString()));
            assertTrue(firstSignature.get().startsWith("sha256="));
            assertEquals(71, firstSignature.get().length());
        } finally {
            http.stop(0);
        }
    }

    @Test
    void missingCallbackConfigurationLeavesPreviousModesUnchanged() {
        SettlementCallbackPublisher disabled = new SettlementCallbackPublisher("", "");
        UUID id = UUID.randomUUID();
        assertDoesNotThrow(() -> disabled.publishTwice(new SettlementResponse(id, 1L, "BANK",
                SettlementStatus.SETTLED, "SIM-" + id, Instant.now(), Instant.now())));
    }
}
