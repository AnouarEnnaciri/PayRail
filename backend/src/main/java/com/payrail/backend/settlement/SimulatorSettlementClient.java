package com.payrail.backend.settlement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

/** HTTP boundary: MUST be called outside the PayRail database transaction. */
@Component
public class SimulatorSettlementClient implements SettlementTransport {

    private final RestClient client;

    public SimulatorSettlementClient(
            @Value("${payrail.simulator.base-url:http://127.0.0.1:8081}") String baseUrl,
            @Value("${payrail.simulator.connect-timeout-ms:1500}") long connectTimeoutMs,
            @Value("${payrail.simulator.read-timeout-ms:1000}") long readTimeoutMs) {
        if (baseUrl == null || baseUrl.isBlank() || connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalArgumentException("Invalid simulator client configuration");
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public Receipt submit(Command request) {
        return client.post().uri("/settlements")
                .body(request).retrieve().body(Receipt.class);
    }

    @Override
    public Receipt get(UUID settlementRequestId) {
        return client.get().uri("/settlements/{id}", settlementRequestId)
                .retrieve().body(Receipt.class);
    }
}
