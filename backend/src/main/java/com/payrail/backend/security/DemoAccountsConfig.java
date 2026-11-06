
package com.payrail.backend.security;

import com.payrail.backend.account.Account;
import com.payrail.backend.account.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.UUID;

@Configuration
@Profile("demo")
public class DemoAccountsConfig {

    private static final Logger log =
            LoggerFactory.getLogger(DemoAccountsConfig.class);

    @Bean
    public ApplicationRunner createDemoAccounts(AccountRepository repository) {
        return args -> {
            createAccount(
                    repository,
                    "DEMO-CLIENT-ALPHA",
                    UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
            );

            createAccount(
                    repository,
                    "DEMO-CLIENT-BETA",
                    UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
            );
        };
    }

    private void createAccount(
            AccountRepository repository,
            String reference,
            UUID ownerId
    ) {
        Account account = repository.findByReference(reference)
                .orElseGet(() -> repository.save(
                        Account.customer(reference, ownerId)
                ));

        if (!ownerId.equals(account.getOwnerId())) {
            throw new IllegalStateException(
                    "Incorrect demo account owner: " + reference
            );
        }

        log.info("Demo account {} ID: {}", reference, account.getId());
    }
}
