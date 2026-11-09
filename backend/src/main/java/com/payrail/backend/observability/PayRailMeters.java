package com.payrail.backend.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Fixed, low-cardinality financial-operation gauges; SQL is read-only on scrape. */
@Component
public final class PayRailMeters implements MeterBinder {
    private static final String[] OUTBOX_STATUSES =
            {"PENDING", "PROCESSING", "PROCESSED", "DEAD_LETTER"};
    private static final String[] TRANSFER_STATES =
            {"COMPLETED", "PENDING_SETTLEMENT", "SETTLED", "FAILED", "RECONCILIATION_REQUIRED"};
    private final JdbcTemplate jdbc;

    public PayRailMeters(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (String status : OUTBOX_STATUSES) {
            Gauge.builder("payrail.outbox.jobs", jdbc, j -> count(j,
                            "SELECT count(*) FROM outbox_events WHERE status=?", status))
                    .description("Durable settlement outbox events by state")
                    .tag("status", status)
                    .register(registry);
        }
        for (String state : TRANSFER_STATES) {
            Gauge.builder("payrail.transfers", jdbc, j -> count(j,
                            "SELECT count(*) FROM transfers WHERE status=?", state))
                    .description("Transfers by financial state")
                    .tag("state", state)
                    .register(registry);
        }
        for (String status : new String[]{"OPEN", "RESOLVED"}) {
            Gauge.builder("payrail.reconciliation.issues", jdbc, j -> count(j,
                            "SELECT count(*) FROM reconciliation_issues WHERE status=?", status))
                    .description("Reconciliation issues by state")
                    .tag("status", status)
                    .register(registry);
        }
    }

    private static double count(JdbcTemplate jdbc, String sql, String status) {
        Long total = jdbc.queryForObject(sql, Long.class, status);
        return total == null ? Double.NaN : total.doubleValue();
    }
}
