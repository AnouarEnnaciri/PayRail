package com.payrail.backend.settlement;

import com.payrail.backend.account.Account;
import com.payrail.backend.account.AccountRepository;
import com.payrail.backend.account.AccountType;
import com.payrail.backend.ledger.LedgerEntry;
import com.payrail.backend.ledger.LedgerPostingService;
import com.payrail.backend.ledger.LedgerTransaction;
import com.payrail.backend.transfer.Transfer;
import com.payrail.backend.transfer.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Finalization requires verified terminal external evidence and a caller-owned transaction. */
@Service
public class ExternalSettlementFinalizationService {

    private final TransferRepository transfers;
    private final AccountRepository accounts;
    private final LedgerPostingService ledger;

    public ExternalSettlementFinalizationService(TransferRepository transfers,
                                                 AccountRepository accounts,
                                                 LedgerPostingService ledger) {
        this.transfers = transfers;
        this.accounts = accounts;
        this.ledger = ledger;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(UUID transferId, UUID settlementRequestId,
                      long receiptAmountMinor, String receiptDestinationReference,
                      String externalStatus, String externalReference) {
        if (!"SETTLED".equals(externalStatus) && !"REJECTED".equals(externalStatus)) {
            throw new IllegalArgumentException("Terminal external receipt required");
        }
        if ("SETTLED".equals(externalStatus)
                && (externalReference == null || externalReference.isBlank())) {
            throw new IllegalArgumentException("Settled receipt requires external reference");
        }

        Transfer transfer = transfers.findByIdForUpdate(transferId)
                .orElseThrow(() -> new IllegalStateException("Transfer does not exist"));
        if (!"EXTERNAL".equals(transfer.getTransferType())
                || !Objects.equals(transfer.getSettlementRequestId(), settlementRequestId)
                || transfer.getAmountMinor() != receiptAmountMinor
                || !Objects.equals(transfer.getDestinationReference(), receiptDestinationReference)) {
            throw new IllegalStateException("Receipt does not match external transfer");
        }
        // M11: PENDING and RECONCILIATION_REQUIRED may both be finalized after verified evidence.
        // The domain state machine blocks all other transitions, including second finalizations.
        transfer.requireExternalFinalizationAllowed();

        Account clearing = systemAccount("SETTLEMENT_CLEARING");
        long amount = transfer.getAmountMinor();
        LedgerTransaction transaction;
        List<LedgerEntry> entries;

        if ("SETTLED".equals(externalStatus)) {
            Account settled = systemAccount("EXTERNAL_SETTLED");
            transaction = LedgerTransaction.externalSettlementFinalization(transferId);
            entries = List.of(
                    LedgerEntry.create(transaction.getId(), clearing.getId(), -amount),
                    LedgerEntry.create(transaction.getId(), settled.getId(), amount));
            transfer.markSettled();
        } else {
            // Compensation credits the customer even if their account was later blocked.
            Account customer = accounts.findByIdForUpdate(transfer.getSenderAccountId())
                    .orElseThrow(() -> new IllegalStateException("Customer account does not exist"));
            if (customer.getAccountType() != AccountType.CUSTOMER || !"MAD".equals(customer.getCurrency())) {
                throw new IllegalStateException("Invalid customer account for compensation");
            }
            transaction = LedgerTransaction.externalCompensation(transferId);
            entries = List.of(
                    LedgerEntry.create(transaction.getId(), clearing.getId(), -amount),
                    LedgerEntry.create(transaction.getId(), customer.getId(), amount));
            customer.credit(amount);
            transfer.markFailedAfterDefinitiveRejection();
        }

        ledger.post(transaction, entries);
        // Dirty checking and unique terminal-effect index are enforced before commit.
        transfers.flush();
    }

    private Account systemAccount(String reference) {
        Account account = accounts.findByReference(reference)
                .orElseThrow(() -> new IllegalStateException("Missing system account " + reference));
        if (account.getAccountType() != AccountType.SYSTEM || !"MAD".equals(account.getCurrency())) {
            throw new IllegalStateException("Invalid system account " + reference);
        }
        return account;
    }
}
