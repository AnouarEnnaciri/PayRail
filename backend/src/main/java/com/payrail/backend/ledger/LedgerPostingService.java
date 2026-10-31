package com.payrail.backend.ledger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class LedgerPostingService {

    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;

    public LedgerPostingService(
            LedgerTransactionRepository transactionRepository,
            LedgerEntryRepository entryRepository
    ) {
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
    }

    @Transactional
    public void post(
            LedgerTransaction transaction,
            List<LedgerEntry> entries
    ) {
        if (transaction == null) {
            throw new IllegalArgumentException("Ledger transaction is required");
        }

        if (entries == null || entries.size() < 2) {
            throw new IllegalArgumentException(
                    "Ledger transaction requires at least two entries"
            );
        }

        long total = 0L;

        for (LedgerEntry entry : entries) {
            if (!transaction.getId().equals(entry.getLedgerTransactionId())) {
                throw new IllegalArgumentException(
                        "Ledger entry belongs to a different transaction"
                );
            }

            total = Math.addExact(
                    total,
                    entry.getSignedAmountMinor()
            );
        }

        if (total != 0L) {
            throw new IllegalArgumentException(
                    "Ledger transaction must balance to zero"
            );
        }

        transactionRepository.save(transaction);
        entryRepository.saveAll(entries);
    }
}