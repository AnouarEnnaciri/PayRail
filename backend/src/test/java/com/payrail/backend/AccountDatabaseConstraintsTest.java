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

        assertThrows(DataIntegrityViolationException.class, () ->
                jdbcTemplate.update("""
                INSERT INTO accounts (
                    id,
                    reference,
                    owner_id,
                    account_type,
                    currency,
                    balance_minor,
                    status,
                    version,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, 'CUSTOMER', 'MAD', -1, 'ACTIVE', 0, NOW(), NOW())
                """,
                        UUID.randomUUID(),
                        "ACC-NEGATIVE-TEST",
                        UUID.randomUUID()
                )
        );
    }
}