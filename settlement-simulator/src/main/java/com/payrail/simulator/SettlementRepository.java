package com.payrail.simulator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
class SettlementRepository {
    private final JdbcTemplate jdbc;

    SettlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // PostgreSQL waits for concurrent conflicting inserts, so a second caller
    // sees the first committed request instead of creating another settlement.
    boolean insertIfAbsent(UUID id, String hash, SubmitSettlementRequest request,
                           SettlementStatus status, String reference, Instant settleAt) {
        int inserted = jdbc.update("""
                INSERT INTO settlement_requests
                    (settlement_request_id, request_hash, amount_minor, destination_reference,
                     mode, status, external_reference, settle_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (settlement_request_id) DO NOTHING
                """, id, hash, request.amountMinor(), request.destinationReference(),
                request.mode().name(), status.name(), reference,
                settleAt == null ? null : java.sql.Timestamp.from(settleAt));
        return inserted == 1;
    }

    Optional<SettlementRecord> find(UUID id) {
        return jdbc.query("""
                SELECT settlement_request_id, request_hash, amount_minor, destination_reference,
                       mode, status, external_reference, created_at, updated_at
                FROM settlement_requests WHERE settlement_request_id = ?
                """, rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(), id);
    }

    // A delayed settlement is materialized on the next status read, using a
    // deadline persisted in PostgreSQL instead of an in-memory scheduler.
    void finalizeDueSettlement(UUID id) {
        jdbc.update("""
                UPDATE settlement_requests
                SET status = 'SETTLED',
                    external_reference = 'SIM-' || settlement_request_id::text,
                    updated_at = clock_timestamp()
                WHERE settlement_request_id = ?
                  AND status = 'PENDING'
                  AND settle_at <= clock_timestamp()
                """, id);
    }

    private static SettlementRecord map(ResultSet rs) throws SQLException {
        return new SettlementRecord(
                rs.getObject("settlement_request_id", UUID.class),
                rs.getString("request_hash"),
                rs.getLong("amount_minor"),
                rs.getString("destination_reference"),
                SettlementMode.valueOf(rs.getString("mode")),
                SettlementStatus.valueOf(rs.getString("status")),
                rs.getString("external_reference"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }
}
