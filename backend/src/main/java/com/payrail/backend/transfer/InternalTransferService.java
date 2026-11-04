
package com.payrail.backend.transfer;


import com.payrail.backend.ledger.LedgerTransaction;
import com.payrail.backend.ledger.LedgerEntry;
import java.util.List;
import com.payrail.backend.account.AccountType;
import com.payrail.backend.account.AccountStatus;
import org.springframework.transaction.annotation.Transactional;
import com.payrail.backend.account.Account;
import java.util.UUID;
import com.payrail.backend.account.AccountRepository;
import com.payrail.backend.ledger.LedgerPostingService;
import org.springframework.stereotype.Service;

@Service
public class InternalTransferService {

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final LedgerPostingService ledgerPostingService;

    public InternalTransferService(
            AccountRepository accountRepository,
            TransferRepository transferRepository,
            LedgerPostingService ledgerPostingService
    ) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.ledgerPostingService = ledgerPostingService;
    }


    private Account lockAccount(UUID accountId) {
        return accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Account not found")
                );
    }
    private Account[] lockBothAccounts(UUID senderId, UUID receiverId) {
        if (senderId == null || receiverId == null) {
            throw new IllegalArgumentException("Both account IDs are required");
        }

        if (senderId.equals(receiverId)) {
            throw new IllegalArgumentException("Cannot transfer to the same account");
        }

        UUID firstId = senderId.compareTo(receiverId) < 0
                ? senderId : receiverId;

        UUID secondId = senderId.compareTo(receiverId) < 0
                ? receiverId : senderId;

        Account first = lockAccount(firstId);
        Account second = lockAccount(secondId);

        return senderId.equals(firstId)
                ? new Account[]{first, second}
                : new Account[]{second, first};
    }
    @Transactional
    public void transfer(UUID senderId, UUID receiverId, long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Transfer amount must be positive");
        }

        Account[] accounts = lockBothAccounts(senderId, receiverId);
        Account sender = accounts[0];
        Account receiver = accounts[1];

        if (sender.getAccountType() != AccountType.CUSTOMER
                || receiver.getAccountType() != AccountType.CUSTOMER) {
            throw new IllegalArgumentException("Both accounts must be customer accounts");
        }

        if (sender.getStatus() != AccountStatus.ACTIVE
                || receiver.getStatus() != AccountStatus.ACTIVE) {
            throw new IllegalStateException("Both accounts must be active");
        }

        sender.debit(amountMinor);
        receiver.credit(amountMinor);

        Transfer transfer = Transfer.completed(
                sender.getId(),
                receiver.getId(),
                amountMinor
        );

        transferRepository.save(transfer);


        LedgerTransaction transaction =
                LedgerTransaction.internalTransfer(transfer.getId());

        LedgerEntry senderDebit = LedgerEntry.create(
                transaction.getId(),
                sender.getId(),
                -amountMinor
        );

        LedgerEntry receiverCredit = LedgerEntry.create(
                transaction.getId(),
                receiver.getId(),
                amountMinor
        );

        ledgerPostingService.post(
                transaction,
                List.of(senderDebit, receiverCredit)
        );
    }

}
