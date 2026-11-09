package com.payrail.backend.api;

import com.payrail.backend.account.Account;
import com.payrail.backend.account.AccountRepository;
import com.payrail.backend.security.DemoOwnerResolver;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@Profile("demo")
@RequestMapping("/api/client/accounts")
public class ClientAccountController {

    public record AccountView(UUID id, String reference, long balanceMinor, String currency) {
        static AccountView from(Account account) {
            return new AccountView(account.getId(), account.getReference(),
                    account.getBalanceMinor(), account.getCurrency());
        }
    }

    private final AccountRepository accountRepository;
    private final DemoOwnerResolver ownerResolver;

    public ClientAccountController(AccountRepository accountRepository,
                                   DemoOwnerResolver ownerResolver) {
        this.accountRepository = accountRepository;
        this.ownerResolver = ownerResolver;
    }

    @GetMapping
    public List<AccountView> list(Principal principal) {
        UUID ownerId = ownerResolver.resolve(principal);
        return accountRepository.findByOwnerIdOrderByReferenceAsc(ownerId)
                .stream().map(AccountView::from).toList();
    }

    @GetMapping("/{accountId}")
    public AccountView getAccount(@PathVariable UUID accountId, Principal principal) {
        UUID ownerId = ownerResolver.resolve(principal);
        Account account = accountRepository.findByIdAndOwnerId(accountId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Account not found"));
        return AccountView.from(account);
    }
}
