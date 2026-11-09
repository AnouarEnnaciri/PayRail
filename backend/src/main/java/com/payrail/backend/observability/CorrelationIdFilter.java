package com.payrail.backend.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Correlates safe request logs without recording request bodies, headers or credentials. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-ID";
    private static final String KEY = "correlation_id";
    private static final Logger LOG = LoggerFactory.getLogger(CorrelationIdFilter.class);
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{7,63}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        String correlationId = supplied != null && VALID.matcher(supplied).matches()
                ? supplied : UUID.randomUUID().toString();
        String previous = MDC.get(KEY);
        MDC.put(KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            LOG.info("http request completed method={} status={} duration_ms={}",
                    request.getMethod(), response.getStatus(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            if (previous == null) MDC.remove(KEY);
            else MDC.put(KEY, previous);
        }
    }
}
