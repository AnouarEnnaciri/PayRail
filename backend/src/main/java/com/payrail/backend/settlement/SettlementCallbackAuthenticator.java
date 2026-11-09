package com.payrail.backend.settlement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Verify the terminal notification independently of Basic auth. No configured secret: fail closed. */
@Component
public class SettlementCallbackAuthenticator {
    private final String secret;

    public SettlementCallbackAuthenticator(@Value("${PAYRAIL_CALLBACK_HMAC_SECRET:}") String secret) {
        if (secret != null && !secret.isBlank()
                && secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("Callback HMAC secret must contain at least 32 bytes");
        }
        this.secret = secret;
    }

    public void verify(SettlementCallbackEvent event, String suppliedSignature) {
        if (secret == null || secret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Settlement callbacks not configured");
        }
        if (suppliedSignature == null || !suppliedSignature.startsWith("sha256=")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid callback signature");
        }
        byte[] expected = signature(event, secret).getBytes(StandardCharsets.US_ASCII);
        byte[] supplied = suppliedSignature.getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid callback signature");
        }
    }

    /** Length-prefixed values avoid ambiguous canonical representations. */
    public static String canonical(SettlementCallbackEvent event) {
        return event.eventId() + "\n"
                + event.settlementRequestId() + "\n"
                + event.amountMinor() + "\n"
                + field(event.destinationReference()) + "\n"
                + field(event.status()) + "\n"
                + field(event.externalReference());
    }

    private static String field(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }

    public static String signature(SettlementCallbackEvent event, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(
                    mac.doFinal(canonical(event).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public static String fingerprint(SettlementCallbackEvent event) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical(event).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
