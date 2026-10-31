package com.payrail.backend.ledger;

import com.payrail.backend.account.Account;
import com.payrail.backend.account.AccountRepository;
import com.payrail.backend.account.AccountType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class InitialFundingService {

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerPostingService ledgerPostingService;

    public InitialFundingService(
            AccountRepository accountRepository,
            LedgerTransactionRepository transactionRepository,
            LedgerPostingService ledgerPostingService
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerPostingService = ledgerPostingService;
    }

    @Transactional
    public void fund(
            UUID customerAccountId,
            UUID systemFundingAccountId,
            long amountMinor
    ) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Funding amount must be positive");
        }

        Account customer = accountRepository
                .findByIdForUpdate(customerAccountId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Customer account not found")
                );

        Account systemFunding = accountRepository
                .findById(systemFundingAccountId)
                .orElseThrow(() ->
                        new IllegalArgumentException("System funding account not found")
                );

        if (customer.getAccountType() != AccountType.CUSTOMER) {
            throw new IllegalArgumentException(
                    "Target account must be a customer account"
            );
        }

        if (systemFunding.getAccountType() != AccountType.SYSTEM) {
            throw new IllegalArgumentException(
                    "Funding source must be a system account"
            );
        }

        String fundingReference =
                "INITIAL-FUNDING:" + customer.getId();

        if (transactionRepository
                .existsByFundingReference(fundingReference)) {
            return;
        }

        LedgerTransaction transaction =
                LedgerTransaction.initialFunding(fundingReference);

        LedgerEntry systemDebit = LedgerEntry.create(
                transaction.getId(),
                systemFunding.getId(),
                -amountMinor
        );

        LedgerEntry customerCredit = LedgerEntry.create(
                transaction.getId(),
                customer.getId(),
                amountMinor
        );

        customer.credit(amountMinor);

        ledgerPostingService.post(
                transaction,
                List.of(systemDebit, customerCredit)
        );
    }
}