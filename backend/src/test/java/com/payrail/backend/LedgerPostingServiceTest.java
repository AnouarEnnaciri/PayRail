package com.payrail.backend;

import com.payrail.backend.ledger.LedgerEntry;
import com.payrail.backend.ledger.LedgerEntryRepository;
import com.payrail.backend.ledger.LedgerPostingService;
import com.payrail.backend.ledger.LedgerTransaction;
import com.payrail.backend.ledger.LedgerTransactionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class LedgerPostingServiceTest {

    @Test
    void unbalancedLedgerTransactionIsRejected() {
        LedgerTransactionRepository transactionRepository =
                mock(LedgerTransactionRepository.class);

        LedgerEntryRepository entryRepository =
                mock(LedgerEntryRepository.class);

        LedgerPostingService service =
                new LedgerPostingService(transactionRepository, entryRepository);

        LedgerTransaction transaction =
                LedgerTransaction.initialFunding("TEST-FUNDING-1");
        LedgerEntry debit = LedgerEntry.create(
                transaction.getId(),
                UUID.randomUUID(),
                -50_000L
        );

        LedgerEntry credit = LedgerEntry.create(
                transaction.getId(),
                UUID.randomUUID(),
                40_000L
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.post(
                        transaction,
                        List.of(debit, credit)
                )
        );

        verifyNoInteractions(
                transactionRepository,
                entryRepository
        );
    }
}