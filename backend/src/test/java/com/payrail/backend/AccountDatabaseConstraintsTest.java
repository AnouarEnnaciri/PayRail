package com.payrail.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AccountDatabaseConstraintsTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void customerBalanceCannotBeNegative() {
        UUID accountId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        assertThrows(DataIntegrityViolationException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO accounts
                            (id, reference, owner_id, account_type, currency,
                             balance_minor, status, version)
                        VALUES (?, ?, ?, 'CUSTOMER', 'MAD', -1, 'ACTIVE', 0)
                        """,
                        accountId,
                        "CUSTOMER-" + accountId,
                        ownerId
                )
        );
    }

    @Test
    void customerMustHaveOwner() {
        UUID accountId = UUID.randomUUID();

        assertThrows(DataIntegrityViolationException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO accounts
                            (id, reference, owner_id, account_type, currency,
                             balance_minor, status, version)
                        VALUES (?, ?, NULL, 'CUSTOMER', 'MAD', 0, 'ACTIVE', 0)
                        """,
                        accountId,
                        "CUSTOMER-" + accountId
                )
        );
    }

    @Test
    void systemAccountMustNotHaveOwner() {
        UUID accountId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        assertThrows(DataIntegrityViolationException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO accounts
                            (id, reference, owner_id, account_type, currency,
                             balance_minor, status, version)
                        VALUES (?, ?, ?, 'SYSTEM', 'MAD', NULL, 'ACTIVE', 0)
                        """,
                        accountId,
                        "SYSTEM-" + accountId,
                        ownerId
                )
        );
    }

    @Test
    void ledgerEntryCannotHaveZeroAmount() {
        UUID accountId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO accounts
                    (id, reference, owner_id, account_type, currency,
                     balance_minor, status, version)
                VALUES (?, ?, NULL, 'SYSTEM', 'MAD', NULL, 'ACTIVE', 0)
                """,
                accountId,
                "SYSTEM-" + accountId
        );

        jdbcTemplate.update("""
                INSERT INTO ledger_transactions
                    (id, effect_type)
                VALUES (?, 'INITIAL_FUNDING')
                """,
                transactionId
        );

        assertThrows(DataIntegrityViolationException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO ledger_entries
                            (id, ledger_transaction_id, account_id,
                             signed_amount_minor, currency)
                        VALUES (?, ?, ?, 0, 'MAD')
                        """,
                        entryId,
                        transactionId,
                        accountId
                )
        );
    }
}