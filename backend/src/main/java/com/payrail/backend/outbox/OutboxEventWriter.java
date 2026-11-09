package com.payrail.backend.outbox;

import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.UUID;

/** Persist the durable settlement request in the caller's PostgreSQL transaction. */
@Component
public class OutboxEventWriter {
    private final JdbcTemplate jdbc;

    public OutboxEventWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void writeSettlementRequested(UUID transferId, UUID settlementRequestId,
                                          long amountMinor, String destinationReference) {
        // The original request correlation is stored durably with the outbox command.
        String correlationId = MDC.get("correlation_id");
        if (correlationId == null || !correlationId.matches("[A-Za-z0-9][A-Za-z0-9._-]{7,63}")) {
            correlationId = UUID.randomUUID().toString();
        }
        jdbc.update("""
            INSERT INTO outbox_events
                (id, aggregate_type, aggregate_id, event_type, payload_json, status)
            VALUES (?, 'TRANSFER', ?, 'SETTLEMENT_REQUESTED',
                jsonb_build_object(
                    'settlementRequestId', CAST(? AS text),
                    'amountMinor', CAST(? AS bigint),
                    'destinationReference', CAST(? AS text),
                    'mode', 'SUCCESS',
                    'correlationId', CAST(? AS text)),
                'PENDING')
            """, UUID.randomUUID(), transferId, settlementRequestId.toString(),
                amountMinor, destinationReference, correlationId);
    }
}
