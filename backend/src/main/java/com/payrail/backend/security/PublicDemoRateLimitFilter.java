
package com.payrail.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("public-demo")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicDemoRateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 60_000;
    private static final int MAX_KEYS = 10_000;

    private final ConcurrentHashMap<String, Window> windows =
            new ConcurrentHashMap<>();

    private final Clock clock;

    public PublicDemoRateLimitFilter() {
        this(Clock.systemUTC());
    }

    PublicDemoRateLimitFilter(Clock clock) {
        this.clock = clock;
    }

    private record Window(long startedAt, int count) {}

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {

        String route = routeGroup(request);

        if (route == null) {
            chain.doFilter(request, response);
            return;
        }

        long now = clock.millis();

        // Never trust user-supplied X-Forwarded-For headers.
        String key = route + ":" + request.getRemoteAddr();

        // Keep the in-memory store bounded.
        if (windows.size() >= MAX_KEYS && !windows.containsKey(key)) {
            windows.entrySet().removeIf(entry ->
                    now - entry.getValue().startedAt() >= WINDOW_MS
            );

            if (windows.size() >= MAX_KEYS) {
                reject(response, 60);
                return;
            }
        }

        Window current = windows.compute(key, (k, previous) -> {
            if (previous == null
                    || now < previous.startedAt()
                    || now - previous.startedAt() >= WINDOW_MS) {
                return new Window(now, 1);
            }

            return new Window(previous.startedAt(),
                    previous.count() + 1);
        });

        int limit = switch (route) {
            case "login" -> 10;
            case "transfer" -> 20;
            case "callback" -> 60;
            default -> throw new IllegalStateException();
        };

        if (current.count() > limit) {
            long remaining = WINDOW_MS - (now - current.startedAt());
            long retrySeconds = Math.max(1,
                    (remaining + 999) / 1000);
            reject(response, retrySeconds);
            return;
        }

        chain.doFilter(request, response);
    }

    private String routeGroup(HttpServletRequest request) {
        String path = request.getRequestURI()
                .substring(request.getContextPath().length());
        String method = request.getMethod();

        if ("GET".equals(method)
                && "/api/ui/session".equals(path)) {
            return "login";
        }

        if ("POST".equals(method)
                && ("/api/client/transfers".equals(path)
                || "/api/client/external-transfers".equals(path))) {
            return "transfer";
        }

        if ("POST".equals(method)
                && "/api/settlement/callbacks".equals(path)) {
            return "callback";
        }

        return null;
    }

    private void reject(
            HttpServletResponse response,
            long retrySeconds
    ) throws IOException {

        response.setStatus(429);
        response.setHeader("Retry-After",
                Long.toString(retrySeconds));
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");

        response.getWriter().write("""
                {"type":"about:blank",
                 "title":"Too Many Requests",
                 "status":429,
                 "detail":"Public demo rate limit exceeded"}
                """);
    }
}
