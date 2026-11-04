
package com.payrail.backend;

import static org.junit.jupiter.api.Assertions.assertThrows;
import com.payrail.backend.account.Account;
import com.payrail.backend.account.AccountRepository;
import com.payrail.backend.ledger.InitialFundingService;
import com.payrail.backend.transfer.IdempotentTransferService;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class IdempotentTransferServiceTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private InitialFundingService initialFundingService;

    @Autowired
    private IdempotentTransferService idempotentTransferService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void repeatedRequestExecutesTransferOnlyOnce() {

        Account sender = Account.customer(
                "SENDER-" + UUID.randomUUID(),
                UUID.randomUUID()
        );

        Account receiver = Account.customer(
                "RECEIVER-" + UUID.randomUUID(),
                UUID.randomUUID()
        );

        Account system = Account.system(
                "SYSTEM-" + UUID.randomUUID()
        );

        accountRepository.saveAllAndFlush(
                List.of(sender, receiver, system)
        );

        // Give sender 1000 MAD.
        initialFundingService.fund(
                sender.getId(),
                system.getId(),
                100_000L
        );

        String key = UUID.randomUUID().toString();

        // First request: transfer 200 MAD.
        UUID firstId = idempotentTransferService.transfer(
                sender.getId(),
                receiver.getId(),
                20_000L,
                key
        );

        // Same request repeated.
        UUID secondId = idempotentTransferService.transfer(
                sender.getId(),
                receiver.getId(),
                20_000L,
                key
        );

        Long senderBalance = jdbcTemplate.queryForObject(
                "SELECT balance_minor FROM accounts WHERE id = ?",
                Long.class,
                sender.getId()
        );

        Long receiverBalance = jdbcTemplate.queryForObject(
                "SELECT balance_minor FROM accounts WHERE id = ?",
                Long.class,
                receiver.getId()
        );

        Long transferCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM transfers
                WHERE sender_account_id = ?
                  AND receiver_account_id = ?
                """,
                Long.class,
                sender.getId(),
                receiver.getId()
        );

        assertEquals(firstId, secondId);
        assertEquals(80_000L, senderBalance);
        assertEquals(20_000L, receiverBalance);
        assertEquals(1L, transferCount);
    }

    @Test
    void sameKeyWithDifferentAmountIsRejected() {

        Account sender = Account.customer(
                "SENDER-" + UUID.randomUUID(),
                UUID.randomUUID()
        );

        Account receiver = Account.customer(
                "RECEIVER-" + UUID.randomUUID(),
                UUID.randomUUID()
        );

        Account system = Account.system(
                "SYSTEM-" + UUID.randomUUID()
        );

        accountRepository.saveAllAndFlush(
                List.of(sender, receiver, system)
        );

        initialFundingService.fund(
                sender.getId(),
                system.getId(),
                100_000L
        );

        String key = UUID.randomUUID().toString();

        // First request: 200 MAD.
        idempotentTransferService.transfer(
                sender.getId(),
                receiver.getId(),
                20_000L,
                key
        );

        // Same key, but different amount: 500 MAD.
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> idempotentTransferService.transfer(
                        sender.getId(),
                        receiver.getId(),
                        50_000L,
                        key
                )
        );

        Long senderBalance = jdbcTemplate.queryForObject(
                "SELECT balance_minor FROM accounts WHERE id = ?",
                Long.class,
                sender.getId()
        );

        Long receiverBalance = jdbcTemplate.queryForObject(
                "SELECT balance_minor FROM accounts WHERE id = ?",
                Long.class,
                receiver.getId()
        );

        Long transferCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transfers WHERE sender_account_id = ?",
                Long.class,
                sender.getId()
        );

        assertEquals(80_000L, senderBalance);
        assertEquals(20_000L, receiverBalance);
        assertEquals(1L, transferCount);
    }

    @Test
    void concurrentRequestsWithSameKeyExecuteOnlyOnce() throws Exception {

        Account sender = Account.customer(
                "SENDER-" + UUID.randomUUID(),
                UUID.randomUUID()
        );

        Account receiver = Account.customer(
                "RECEIVER-" + UUID.randomUUID(),
                UUID.randomUUID()
        );

        Account system = Account.system(
                "SYSTEM-" + UUID.randomUUID()
        );

        accountRepository.saveAllAndFlush(
                List.of(sender, receiver, system)
        );

        // Give sender 1000 MAD.
        initialFundingService.fund(
                sender.getId(),
                system.getId(),
                100_000L
        );

        String key = UUID.randomUUID().toString();

        int requestCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<UUID>> results = new ArrayList<>();

            for (int i = 0; i < requestCount; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();

                    return idempotentTransferService.transfer(
                            sender.getId(),
                            receiver.getId(),
                            20_000L,
                            key
                    );
                }));
            }

            assertTrue(ready.await(15, TimeUnit.SECONDS));
            start.countDown();

            UUID firstId = results.get(0).get(60, TimeUnit.SECONDS);
            assertNotNull(firstId);

            for (Future<UUID> result : results) {
                assertEquals(firstId, result.get(60, TimeUnit.SECONDS));
            }

            Long senderBalance = jdbcTemplate.queryForObject(
                    "SELECT balance_minor FROM accounts WHERE id = ?",
                    Long.class,
                    sender.getId()
            );

            Long receiverBalance = jdbcTemplate.queryForObject(
                    "SELECT balance_minor FROM accounts WHERE id = ?",
                    Long.class,
                    receiver.getId()
            );

            Long transferCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM transfers WHERE sender_account_id = ?",
                    Long.class,
                    sender.getId()
            );

            Long keyCount = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) FROM transfer_idempotency
                    WHERE actor_id = ? AND idempotency_key = ?
                    """,
                    Long.class,
                    sender.getId(),
                    key
            );

            assertEquals(80_000L, senderBalance);
            assertEquals(20_000L, receiverBalance);
            assertEquals(1L, transferCount);
            assertEquals(1L, keyCount);

        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }


    @Test
    void failedTransferAllowsRetryWithSameKey() {

        Account sender = Account.customer(
                "SENDER-" + UUID.randomUUID(),
                UUID.randomUUID()
        );

        Account receiver = Account.customer(
                "RECEIVER-" + UUID.randomUUID(),
                UUID.randomUUID()
        );

        Account system = Account.system(
                "SYSTEM-" + UUID.randomUUID()
        );

        accountRepository.saveAllAndFlush(
                List.of(sender, receiver, system)
        );

        String key = UUID.randomUUID().toString();

        // First attempt: insufficient funds.
        assertThrows(IllegalStateException.class, () ->
                idempotentTransferService.transfer(
                        sender.getId(),
                        receiver.getId(),
                        20_000L,
                        key
                )
        );

        // The failed transaction must not reserve the key.
        Long keyCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM transfer_idempotency
                WHERE actor_id = ? AND idempotency_key = ?
                """,
                Long.class,
                sender.getId(),
                key
        );

        Long failedTransferCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transfers WHERE sender_account_id = ?",
                Long.class,
                sender.getId()
        );

        assertEquals(0L, keyCount);
        assertEquals(0L, failedTransferCount);

        // Add 200 MAD to the sender's account.
        initialFundingService.fund(
                sender.getId(),
                system.getId(),
                20_000L
        );

        // Retry with the same key.
        UUID transferId = idempotentTransferService.transfer(
                sender.getId(),
                receiver.getId(),
                20_000L,
                key
        );

        assertNotNull(transferId);

        Long senderBalance = jdbcTemplate.queryForObject(
                "SELECT balance_minor FROM accounts WHERE id = ?",
                Long.class,
                sender.getId()
        );

        Long receiverBalance = jdbcTemplate.queryForObject(
                "SELECT balance_minor FROM accounts WHERE id = ?",
                Long.class,
                receiver.getId()
        );

        assertEquals(0L, senderBalance);
        assertEquals(20_000L, receiverBalance);
    }



}
