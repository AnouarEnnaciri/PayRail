package com.payrail.backend.ledger;

public enum LedgerEffectType {
    INITIAL_FUNDING,
    INTERNAL_TRANSFER,
    EXTERNAL_RESERVATION,
    EXTERNAL_SETTLEMENT_FINALIZATION,
    EXTERNAL_COMPENSATION
}
