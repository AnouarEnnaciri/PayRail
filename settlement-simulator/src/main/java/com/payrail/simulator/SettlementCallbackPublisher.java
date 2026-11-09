package com.payrail.simulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

/** M12 demo-only duplicate notifications: emitted after simulator's DB commit. */
@Component
class SettlementCallbackPublisher {
    private static final Logger log = LoggerFactory.getLogger(SettlementCallbackPublisher.class);

    record Event(UUID eventId, UUID settlementRequestId, long amountMinor,
                 String destinationReference, String status, String externalReference) { }

    private final String callbackUrl;
    private final String secret;
    private final RestClient client;

    SettlementCallbackPublisher(@Value("${SIMULATOR_CALLBACK_URL:}") String callbackUrl,
                                @Value("${SIMULATOR_CALLBACK_HMAC_SECRET:}") String secret) {
        if (callbackUrl == null || secret == null || (callbackUrl.isBlank() != secret.isBlank())) {
            throw new IllegalArgumentException("Provide both callback URL and HMAC secret, or neither");
        }
        if (!secret.isBlank() && secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("Callback HMAC secret must contain at least 32 bytes");
        }
        this.callbackUrl = callbackUrl;
        this.secret = secret;
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(1500));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    void publishTwice(SettlementResponse receipt) {
        if (callbackUrl.isBlank()) return; // opt-in; existing simulator modes remain unchanged
        if (receipt == null || receipt.status() != SettlementStatus.SETTLED) {
            throw new IllegalArgumentException("Duplicate callback demo requires settled receipt");
        }
        // The two deliveries intentionally have the SAME event identity and signature.
        UUID eventId = UUID.nameUUIDFromBytes(
                ("PAYRAIL-CALLBACK:" + receipt.settlementRequestId()).getBytes(StandardCharsets.UTF_8));
        Event event = new Event(eventId, receipt.settlementRequestId(), receipt.amountMinor(),
                receipt.destinationReference(), receipt.status().name(), receipt.externalReference());
        String signature = sign(event, secret);
        for (int i = 0; i < 2; i++) {
            try {
                client.post().uri(callbackUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PayRail-Signature", signature)
                        .body(event)
                        .retrieve().toBodilessEntity();
            } catch (RestClientException ex) {
                // The simulator's durable GET remains the source of truth if callback delivery fails.
                log.warn("Demo callback delivery {} failed for settlement {}: {}",
                        i + 1, receipt.settlementRequestId(), ex.getClass().getSimpleName());
            }
        }
    }

    static String sign(Event event, String secret) {
        try {
            String canonical = event.eventId() + "\n"
                    + event.settlementRequestId() + "\n"
                    + event.amountMinor() + "\n"
                    + field(event.destinationReference()) + "\n"
                    + field(event.status()) + "\n"
                    + field(event.externalReference());
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(
                    mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static String field(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }
}
