package com.payrail.backend.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LedgerTransactionRepository
        extends JpaRepository<LedgerTransaction, UUID> {

    boolean existsByFundingReference(String fundingReference);
}