package com.payrail.backend;

import com.payrail.backend.account.Account;
import com.payrail.backend.account.AccountRepository;
import com.payrail.backend.ledger.InitialFundingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class InitialFundingServiceTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private InitialFundingService initialFundingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void initialFundingIsBalancedAndIdempotent() {

        Account customer = Account.customer(
                "CUSTOMER-" + UUID.randomUUID(),
                UUID.randomUUID()
        );

        Account systemFunding = Account.system(
                "SYSTEM-FUNDING-" + UUID.randomUUID()
        );

        accountRepository.saveAndFlush(customer);
        accountRepository.saveAndFlush(systemFunding);

        long amountMinor = 100_000L;

        initialFundingService.fund(
                customer.getId(),
                systemFunding.getId(),
                amountMinor
        );

        // Run the exact same funding again.
        initialFundingService.fund(
                customer.getId(),
                systemFunding.getId(),
                amountMinor
        );

        Long customerBalance = jdbcTemplate.queryForObject(
                """
                SELECT balance_minor
                FROM accounts
                WHERE id = ?
                """,
                Long.class,
                customer.getId()
        );

        Long customerLedgerBalance = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(signed_amount_minor), 0)
                FROM ledger_entries
                WHERE account_id = ?
                """,
                Long.class,
                customer.getId()
        );

        Long systemLedgerBalance = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(signed_amount_minor), 0)
                FROM ledger_entries
                WHERE account_id = ?
                """,
                Long.class,
                systemFunding.getId()
        );

        String fundingReference =
                "INITIAL-FUNDING:" + customer.getId();

        Long transactionCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM ledger_transactions
                WHERE funding_reference = ?
                """,
                Long.class,
                fundingReference
        );

        Long entryCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM ledger_entries e
                JOIN ledger_transactions t
                  ON e.ledger_transaction_id = t.id
                WHERE t.funding_reference = ?
                """,
                Long.class,
                fundingReference
        );

        Long transactionSum = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(e.signed_amount_minor), 0)
                FROM ledger_entries e
                JOIN ledger_transactions t
                  ON e.ledger_transaction_id = t.id
                WHERE t.funding_reference = ?
                """,
                Long.class,
                fundingReference
        );

        assertEquals(amountMinor, customerBalance);
        assertEquals(amountMinor, customerLedgerBalance);
        assertEquals(-amountMinor, systemLedgerBalance);

        assertEquals(1L, transactionCount);
        assertEquals(2L, entryCount);

        assertEquals(0L, transactionSum);
    }
}