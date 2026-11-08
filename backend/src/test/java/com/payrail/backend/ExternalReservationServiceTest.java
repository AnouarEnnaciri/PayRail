package com.payrail.backend;

import com.payrail.backend.account.Account;
import com.payrail.backend.account.AccountRepository;
import com.payrail.backend.account.InsufficientFundsException;
import com.payrail.backend.ledger.InitialFundingService;
import com.payrail.backend.transfer.ExternalReservationService;
import com.payrail.backend.transfer.IdempotencyConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ExternalReservationServiceTest {
    @Autowired AccountRepository accounts;
    @Autowired InitialFundingService funding;
    @Autowired ExternalReservationService reservation;
    @Autowired JdbcTemplate jdbc;

    private Account createSender(long initialBalance) {
        Account sender = accounts.saveAndFlush(
                Account.customer("EXTERNAL-SENDER-" + UUID.randomUUID(), UUID.randomUUID()));
        if (initialBalance > 0) {
            Account system = accounts.saveAndFlush(Account.system("EXTERNAL-FUND-" + UUID.randomUUID()));
            funding.fund(sender.getId(), system.getId(), initialBalance);
        }
        return sender;
    }

    private long count(String table, UUID id) {
        String column = switch (table) {
            case "transfers" -> "sender_account_id";
            case "outbox_events" -> "aggregate_id";
            case "ledger_transactions" -> "transfer_id";
            default -> throw new IllegalArgumentException(table);
        };
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Long.class, id);
    }

    private long balance(UUID accountId) {
        return jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?", Long.class,
                accountId);
    }

    @Test
    void reservationIsAtomicBalancedAndHasDurableOutbox() {
        Account sender = createSender(100_000L);
        var result = reservation.reserve(sender.getId(), "BANK-DEMO-001", 50_000L,
                UUID.randomUUID().toString());

        assertEquals("PENDING_SETTLEMENT", result.status());
        assertNotNull(result.settlementRequestId());
        assertEquals(50_000L, balance(sender.getId()));
        assertEquals(1L, count("transfers", sender.getId()));
        assertEquals(1L, count("ledger_transactions", result.transferId()));
        assertEquals(1L, count("outbox_events", result.transferId()));

        String transferType = jdbc.queryForObject("SELECT transfer_type FROM transfers WHERE id = ?",
                String.class, result.transferId());
        assertEquals("EXTERNAL", transferType);
        UUID receiver = jdbc.queryForObject("SELECT receiver_account_id FROM transfers WHERE id = ?",
                (rs, row) -> rs.getObject(1, UUID.class), result.transferId());
        assertNull(receiver);

        long ledgerSum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(e.signed_amount_minor), 0)
                FROM ledger_entries e JOIN ledger_transactions t ON t.id = e.ledger_transaction_id
                WHERE t.transfer_id = ? AND t.effect_type = 'EXTERNAL_RESERVATION'
                """, Long.class, result.transferId());
        assertEquals(0L, ledgerSum);

        long customerDebit = jdbc.queryForObject("""
                SELECT SUM(e.signed_amount_minor) FROM ledger_entries e
                JOIN ledger_transactions t ON t.id = e.ledger_transaction_id
                WHERE t.transfer_id = ? AND e.account_id = ?
                """, Long.class, result.transferId(), sender.getId());
        assertEquals(-50_000L, customerDebit);

        UUID clearingId = accounts.findByReference("SETTLEMENT_CLEARING").orElseThrow().getId();
        long clearingCredit = jdbc.queryForObject("""
                SELECT SUM(e.signed_amount_minor) FROM ledger_entries e
                JOIN ledger_transactions t ON t.id = e.ledger_transaction_id
                WHERE t.transfer_id = ? AND e.account_id = ?
                """, Long.class, result.transferId(), clearingId);
        assertEquals(50_000L, clearingCredit);
        assertNull(jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?",
                (rs, row) -> rs.getObject(1), clearingId));

        String payloadId = jdbc.queryForObject("""
                SELECT payload_json ->> 'settlementRequestId' FROM outbox_events
                WHERE aggregate_id = ?
                """, String.class, result.transferId());
        assertEquals(result.settlementRequestId().toString(), payloadId);
        assertEquals("SUCCESS", jdbc.queryForObject("""
                SELECT payload_json ->> 'mode' FROM outbox_events WHERE aggregate_id = ?
                """, String.class, result.transferId()));
        assertEquals("PENDING", jdbc.queryForObject("""
                SELECT status FROM outbox_events WHERE aggregate_id = ?
                """, String.class, result.transferId()));
    }

    @Test
    void duplicateRequestReturnsSameTransferAndSettlementIdWithoutDoubleDebit() {
        Account sender = createSender(100_000L);
        String key = UUID.randomUUID().toString();
        var first = reservation.reserve(sender.getId(), "BANK-001", 25_000L, key);
        var again = reservation.reserve(sender.getId(), "BANK-001", 25_000L, key);
        assertEquals(first, again);
        assertEquals(75_000L, balance(sender.getId()));
        assertEquals(1L, count("transfers", sender.getId()));
        assertEquals(1L, count("outbox_events", first.transferId()));
    }

    @Test
    void sameKeyDifferentPayloadIsConflict() {
        Account sender = createSender(100_000L);
        String key = UUID.randomUUID().toString();
        var first = reservation.reserve(sender.getId(), "BANK-001", 25_000L, key);
        assertThrows(IdempotencyConflictException.class,
                () -> reservation.reserve(sender.getId(), "BANK-002", 25_000L, key));
        assertEquals(75_000L, balance(sender.getId()));
        assertEquals(1L, count("outbox_events", first.transferId()));
    }

    @Test
    void insufficientFundsRollsBackKeyAndAllowsRetry() {
        Account sender = createSender(0);
        String key = UUID.randomUUID().toString();
        assertThrows(InsufficientFundsException.class,
                () -> reservation.reserve(sender.getId(), "BANK-001", 10_000L, key));
        assertEquals(0L, balance(sender.getId()));
        assertEquals(0L, count("transfers", sender.getId()));
        assertEquals(0L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM transfer_idempotency
                WHERE actor_id = ? AND idempotency_key = ?
                """, Long.class, sender.getId(), key));
        Account system = accounts.saveAndFlush(Account.system("EXTERNAL-FUND-" + UUID.randomUUID()));
        funding.fund(sender.getId(), system.getId(), 10_000L);
        var result = reservation.reserve(sender.getId(), "BANK-001", 10_000L, key);
        assertNotNull(result.transferId());
        assertEquals(0L, balance(sender.getId()));
    }

    @Test
    void simultaneousDuplicatesCreateOnlyOneReservation() throws Exception {
        Account sender = createSender(100_000L);
        String key = UUID.randomUUID().toString();
        var pool = Executors.newFixedThreadPool(6);
        var start = new CountDownLatch(1);
        try {
            List<Future<ExternalReservationService.ReservationResult>> futures =
                    java.util.stream.IntStream.range(0, 6).mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return reservation.reserve(sender.getId(), "BANK-CONCURRENT", 20_000L, key);
                    })).toList();
            start.countDown();
            var expected = futures.get(0).get(30, TimeUnit.SECONDS);
            for (var future : futures) assertEquals(expected, future.get(30, TimeUnit.SECONDS));
            assertEquals(80_000L, balance(sender.getId()));
            assertEquals(1L, count("transfers", sender.getId()));
            assertEquals(1L, count("outbox_events", expected.transferId()));
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void distinctConcurrentKeysCannotSpendSameMoneyTwice() throws Exception {
        Account sender = createSender(100_000L);
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        try {
                            reservation.reserve(sender.getId(), "BANK-" + i, 80_000L,
                                    UUID.randomUUID().toString());
                            return true;
                        } catch (InsufficientFundsException e) {
                            return false;
                        }
                    })).toList();
            start.countDown();
            int success = 0;
            for (var future : futures) if (future.get(30, TimeUnit.SECONDS)) success++;
            assertEquals(1, success);
            assertEquals(20_000L, balance(sender.getId()));
            assertEquals(1L, count("transfers", sender.getId()));
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }
}
