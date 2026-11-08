package com.payrail.backend;

import com.payrail.backend.account.Account;
import com.payrail.backend.account.AccountRepository;
import com.payrail.backend.ledger.InitialFundingService;
import com.payrail.backend.outbox.OutboxEventWriter;
import com.payrail.backend.transfer.ExternalReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ExternalReservationRollbackTest {
    @Autowired AccountRepository accounts;
    @Autowired InitialFundingService funding;
    @Autowired ExternalReservationService reservation;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean OutboxEventWriter outbox;

    @Test
    void outboxFailureRollsBackDebitTransferLedgerAndIdempotency() {
        Account sender = accounts.saveAndFlush(
                Account.customer("OUTBOX-ROLLBACK-" + UUID.randomUUID(), UUID.randomUUID()));
        Account system = accounts.saveAndFlush(Account.system("OUTBOX-FUND-" + UUID.randomUUID()));
        funding.fund(sender.getId(), system.getId(), 100_000L);
        String key = UUID.randomUUID().toString();
        doThrow(new IllegalStateException("Simulated outbox write failure"))
                .when(outbox).writeSettlementRequested(any(UUID.class), any(UUID.class),
                        anyLong(), anyString());

        assertThrows(IllegalStateException.class,
                () -> reservation.reserve(sender.getId(), "BANK-001", 40_000L, key));

        assertEquals(100_000L, jdbc.queryForObject(
                "SELECT balance_minor FROM accounts WHERE id = ?", Long.class, sender.getId()));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfers WHERE sender_account_id = ?", Long.class, sender.getId()));
        assertEquals(0L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM transfer_idempotency
                WHERE actor_id = ? AND idempotency_key = ?
                """, Long.class, sender.getId(), key));
        assertEquals(0L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM ledger_transactions t
                JOIN transfers f ON f.id = t.transfer_id
                WHERE f.sender_account_id = ? AND t.effect_type = 'EXTERNAL_RESERVATION'
                """, Long.class, sender.getId()));
        assertEquals(0L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox_events e
                JOIN transfers f ON f.id = e.aggregate_id
                WHERE f.sender_account_id = ?
                """, Long.class, sender.getId()));
    }
}
