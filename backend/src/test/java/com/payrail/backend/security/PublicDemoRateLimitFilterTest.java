
package com.payrail.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PublicDemoRateLimitFilterTest {

    private static final String IP = "192.0.2.10";

    private MockHttpServletResponse send(
            PublicDemoRateLimitFilter filter,
            String method,
            String path,
            String ip
    ) throws Exception {

        var request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(ip);

        var response = new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                new MockFilterChain()
        );

        return response;
    }

    @Test
    void loginIsLimitedToTenRequestsPerMinute()
            throws Exception {

        var filter = new PublicDemoRateLimitFilter();

        for (int i = 0; i < 10; i++) {
            assertEquals(200, send(filter,
                    "GET", "/api/ui/session", IP).getStatus());
        }

        var blocked = send(filter,
                "GET", "/api/ui/session", IP);

        assertEquals(429, blocked.getStatus());
        assertNotNull(blocked.getHeader("Retry-After"));
    }

    @Test
    void internalAndExternalTransfersShareLimit()
            throws Exception {

        var filter = new PublicDemoRateLimitFilter();

        for (int i = 0; i < 10; i++) {
            assertEquals(200, send(filter, "POST",
                    "/api/client/transfers", IP).getStatus());
        }

        for (int i = 0; i < 10; i++) {
            assertEquals(200, send(filter, "POST",
                    "/api/client/external-transfers", IP).getStatus());
        }

        assertEquals(429, send(filter, "POST",
                "/api/client/transfers", IP).getStatus());
    }

    @Test
    void differentIpsHaveIndependentLimits()
            throws Exception {

        var filter = new PublicDemoRateLimitFilter();

        for (int i = 0; i < 10; i++) {
            send(filter, "GET", "/api/ui/session", IP);
        }

        assertEquals(429, send(filter,
                "GET", "/api/ui/session", IP).getStatus());

        assertEquals(200, send(filter,
                "GET", "/api/ui/session",
                "192.0.2.11").getStatus());
    }

    @Test
    void unrelatedEndpointIsNotLimited()
            throws Exception {

        var filter = new PublicDemoRateLimitFilter();

        for (int i = 0; i < 30; i++) {
            assertEquals(200, send(filter,
                    "GET", "/actuator/health", IP).getStatus());
        }
    }

    @Test
    void limitResetsAfterOneMinute() throws Exception {

        var clock = new AdjustableClock();
        var filter = new PublicDemoRateLimitFilter(clock);

        for (int i = 0; i < 10; i++) {
            send(filter, "GET", "/api/ui/session", IP);
        }

        assertEquals(429, send(filter,
                "GET", "/api/ui/session", IP).getStatus());

        clock.advance(Duration.ofMinutes(1));

        assertEquals(200, send(filter,
                "GET", "/api/ui/session", IP).getStatus());
    }

    private static class AdjustableClock extends Clock {

        private final AtomicLong now =
                new AtomicLong(Instant.parse(
                        "2026-11-18T12:00:00Z"
                ).toEpochMilli());

        void advance(Duration duration) {
            now.addAndGet(duration.toMillis());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now.get());
        }
    }
}
