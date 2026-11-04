
package com.payrail.backend;

import com.payrail.backend.account.Account;
import com.payrail.backend.account.AccountRepository;
import com.payrail.backend.ledger.InitialFundingService;
import com.payrail.backend.transfer.InternalTransferService;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.payrail.backend.ledger.LedgerPostingService;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class InternalTransferServiceTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private InitialFundingService initialFundingService;

    @Autowired
    private InternalTransferService internalTransferService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private LedgerPostingService ledgerPostingService;

    @Test
    void successfulTransferMovesMoneyAndBalancesLedger() {

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

        // Give the sender 1000 MAD.
        initialFundingService.fund(
                sender.getId(),
                system.getId(),
                100_000L
        );

        // Transfer 250 MAD.
        internalTransferService.transfer(
                sender.getId(),
                receiver.getId(),
                25_000L
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

        UUID transferId = jdbcTemplate.queryForObject(
                """
                SELECT id FROM transfers
                WHERE sender_account_id = ?
                AND receiver_account_id = ?
                """,
                UUID.class,
                sender.getId(),
                receiver.getId()
        );

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM transfers WHERE id = ?",
                String.class,
                transferId
        );

        List<Long> ledgerAmounts = jdbcTemplate.queryForList(
                """
                SELECT e.signed_amount_minor
                FROM ledger_entries e
                JOIN ledger_transactions t
                  ON e.ledger_transaction_id = t.id
                WHERE t.transfer_id = ?
                ORDER BY e.signed_amount_minor
                """,
                Long.class,
                transferId
        );

        assertEquals(75_000L, senderBalance);
        assertEquals(25_000L, receiverBalance);
        assertEquals("COMPLETED", status);
        assertEquals(
                List.of(-25_000L, 25_000L),
                ledgerAmounts
        );
    }


    @Test
    void transferFailsWhenSenderHasInsufficientFunds() {

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

        // Sender receives 100 MAD.
        initialFundingService.fund(
                sender.getId(),
                system.getId(),
                10_000L
        );

        // Attempt to transfer 150 MAD.
        assertThrows(IllegalStateException.class, () ->
                internalTransferService.transfer(
                        sender.getId(),
                        receiver.getId(),
                        15_000L
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
                """
                SELECT COUNT(*) FROM transfers
                WHERE sender_account_id = ?
                AND receiver_account_id = ?
                """,
                Long.class,
                sender.getId(),
                receiver.getId()
        );

        assertEquals(10_000L, senderBalance);
        assertEquals(0L, receiverBalance);
        assertEquals(0L, transferCount);
    }


    @Test
    void transferRollsBackWhenLedgerPostingFails() {

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

        // Give sender 500 MAD.
        initialFundingService.fund(
                sender.getId(),
                system.getId(),
                50_000L
        );

        // Simulate ledger failure after balances change.
        doThrow(new IllegalStateException("Simulated ledger failure"))
                .when(ledgerPostingService)
                .post(any(), any());

        assertThrows(IllegalStateException.class, () ->
                internalTransferService.transfer(
                        sender.getId(),
                        receiver.getId(),
                        20_000L
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
                """
                SELECT COUNT(*) FROM transfers
                WHERE sender_account_id = ?
                  AND receiver_account_id = ?
                """,
                Long.class,
                sender.getId(),
                receiver.getId()
        );

        Long senderLedgerBalance = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(signed_amount_minor), 0)
                FROM ledger_entries
                WHERE account_id = ?
                """,
                Long.class,
                sender.getId()
        );

        Long receiverLedgerBalance = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(signed_amount_minor), 0)
                FROM ledger_entries
                WHERE account_id = ?
                """,
                Long.class,
                receiver.getId()
        );

        assertEquals(50_000L, senderBalance);
        assertEquals(0L, receiverBalance);
        assertEquals(0L, transferCount);
        assertEquals(50_000L, senderLedgerBalance);
        assertEquals(0L, receiverLedgerBalance);
    }


}
