
package com.payrail.backend.api;

import com.payrail.backend.account.Account;
import com.payrail.backend.account.AccountRepository;
import com.payrail.backend.security.DemoOwnerResolver;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

@RestController
@Profile("demo")
@RequestMapping("/api/client/accounts")
public class ClientAccountController {

    private final AccountRepository accountRepository;
    private final DemoOwnerResolver ownerResolver;

    public ClientAccountController(
            AccountRepository accountRepository,
            DemoOwnerResolver ownerResolver
    ) {
        this.accountRepository = accountRepository;
        this.ownerResolver = ownerResolver;
    }

    @GetMapping("/{accountId}")
    public Map<String, Object> getAccount(
            @PathVariable UUID accountId,
            Principal principal
    ) {
        UUID ownerId = ownerResolver.resolve(principal);

        Account account = accountRepository
                .findByIdAndOwnerId(accountId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Account not found"
                ));

        return Map.of(
                "id", account.getId(),
                "reference", account.getReference(),
                "balanceMinor", account.getBalanceMinor(),
                "currency", account.getCurrency()
        );
    }
}
