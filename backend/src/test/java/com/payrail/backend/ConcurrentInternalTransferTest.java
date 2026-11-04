
package com.payrail.backend;

import com.payrail.backend.account.Account;
import com.payrail.backend.account.AccountRepository;
import com.payrail.backend.ledger.InitialFundingService;
import com.payrail.backend.transfer.InternalTransferService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ConcurrentInternalTransferTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private InitialFundingService initialFundingService;

    @Autowired
    private InternalTransferService transferService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void concurrentTransfersCannotOverspend() throws Exception {

        Account sender = Account.customer(
                "SENDER-" + UUID.randomUUID(),
                UUID.randomUUID()
        );

        Account receiverB = Account.customer(
                "RECEIVER-B-" + UUID.randomUUID(),
                UUID.randomUUID()
        );

        Account receiverC = Account.customer(
                "RECEIVER-C-" + UUID.randomUUID(),
                UUID.randomUUID()
        );

        Account system = Account.system(
                "SYSTEM-" + UUID.randomUUID()
        );

        accountRepository.saveAllAndFlush(
                List.of(sender, receiverB, receiverC, system)
        );

        // Sender starts with 100 MAD.
        initialFundingService.fund(
                sender.getId(),
                system.getId(),
                10_000L
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Boolean> first = executor.submit(() ->
                    attemptTransfer(
                            sender.getId(),
                            receiverB.getId(),
                            ready, start
                    )
            );

            Future<Boolean> second = executor.submit(() ->
                    attemptTransfer(
                            sender.getId(),
                            receiverC.getId(),
                            ready, start
                    )
            );

            // Both workers are ready before starting.
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            boolean result1 = first.get(20, TimeUnit.SECONDS);
            boolean result2 = second.get(20, TimeUnit.SECONDS);

            int successes = (result1 ? 1 : 0)
                    + (result2 ? 1 : 0);

            Long senderBalance = jdbcTemplate.queryForObject(
                    "SELECT balance_minor FROM accounts WHERE id = ?",
                    Long.class,
                    sender.getId()
            );

            Long balanceB = jdbcTemplate.queryForObject(
                    "SELECT balance_minor FROM accounts WHERE id = ?",
                    Long.class,
                    receiverB.getId()
            );

            Long balanceC = jdbcTemplate.queryForObject(
                    "SELECT balance_minor FROM accounts WHERE id = ?",
                    Long.class,
                    receiverC.getId()
            );

            Long transferCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM transfers WHERE sender_account_id = ?",
                    Long.class,
                    sender.getId()
            );

            assertEquals(1, successes);
            assertEquals(2_000L, senderBalance);
            assertEquals(8_000L, balanceB + balanceC);
            assertEquals(1L, transferCount);

        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }


    @Test
    void oppositeTransfersDoNotDeadlock() throws Exception {

        Account accountA = Account.customer(
                "A-" + UUID.randomUUID(),
                UUID.randomUUID()
        );

        Account accountB = Account.customer(
                "B-" + UUID.randomUUID(),
                UUID.randomUUID()
        );

        Account system = Account.system(
                "SYSTEM-" + UUID.randomUUID()
        );

        accountRepository.saveAllAndFlush(
                List.of(accountA, accountB, system)
        );

        initialFundingService.fund(
                accountA.getId(), system.getId(), 10_000L
        );

        initialFundingService.fund(
                accountB.getId(), system.getId(), 10_000L
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Boolean> first = executor.submit(() ->
                    attemptTransfer(
                            accountA.getId(),
                            accountB.getId(),
                            ready, start
                    )
            );

            Future<Boolean> second = executor.submit(() ->
                    attemptTransfer(
                            accountB.getId(),
                            accountA.getId(),
                            ready, start
                    )
            );

            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            assertTrue(first.get(20, TimeUnit.SECONDS));
            assertTrue(second.get(20, TimeUnit.SECONDS));

            Long balanceA = jdbcTemplate.queryForObject(
                    "SELECT balance_minor FROM accounts WHERE id = ?",
                    Long.class, accountA.getId()
            );

            Long balanceB = jdbcTemplate.queryForObject(
                    "SELECT balance_minor FROM accounts WHERE id = ?",
                    Long.class, accountB.getId()
            );

            assertEquals(10_000L, balanceA);
            assertEquals(10_000L, balanceB);

        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }



    @Test
    void multipleConcurrentTransfersPreserveBalances() throws Exception {

        Account sender = Account.customer(
                "SENDER-" + UUID.randomUUID(),
                UUID.randomUUID()
        );

        Account system = Account.system(
                "SYSTEM-" + UUID.randomUUID()
        );

        List<Account> receivers = new ArrayList<>();

        for (int i = 0; i < 6; i++) {
            receivers.add(Account.customer(
                    "RECEIVER-" + UUID.randomUUID(),
                    UUID.randomUUID()
            ));
        }

        List<Account> accounts = new ArrayList<>(receivers);
        accounts.add(sender);
        accounts.add(system);

        accountRepository.saveAllAndFlush(accounts);

        // Sender starts with 100 MAD.
        initialFundingService.fund(
                sender.getId(), system.getId(), 10_000L
        );

        ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch ready = new CountDownLatch(6);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Boolean>> results = new ArrayList<>();

            for (Account receiver : receivers) {
                results.add(executor.submit(() ->
                        attemptTransfer(
                                sender.getId(),
                                receiver.getId(),
                                ready, start, 3_000L
                        )
                ));
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            int successes = 0;

            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    successes++;
                }
            }

            Long senderBalance = jdbcTemplate.queryForObject(
                    "SELECT balance_minor FROM accounts WHERE id = ?",
                    Long.class,
                    sender.getId()
            );

            long receiversTotal = 0;

            for (Account receiver : receivers) {
                Long balance = jdbcTemplate.queryForObject(
                        "SELECT balance_minor FROM accounts WHERE id = ?",
                        Long.class,
                        receiver.getId()
                );

                receiversTotal += balance;
            }

            Long transferCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM transfers WHERE sender_account_id = ?",
                    Long.class,
                    sender.getId()
            );

            assertEquals(3, successes);
            assertEquals(1_000L, senderBalance);
            assertEquals(9_000L, receiversTotal);
            assertEquals(3L, transferCount);

        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }



    private boolean attemptTransfer(
            UUID senderId,
            UUID receiverId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {

        return attemptTransfer(
                senderId, receiverId, ready, start, 8_000L
        );
    }

    private boolean attemptTransfer(
            UUID senderId,
            UUID receiverId,
            CountDownLatch ready,
            CountDownLatch start,
            long amountMinor
    ) throws InterruptedException {

        ready.countDown();
        start.await();

        try {
            transferService.transfer(
                    senderId,
                    receiverId,
                    amountMinor
            );
            return true;

        } catch (IllegalStateException exception) {
            if ("Insufficient funds".equals(exception.getMessage())) {
                return false;
            }
            throw exception;
        }
    }

}

