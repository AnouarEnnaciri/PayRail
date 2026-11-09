package com.payrail.backend.transfer;

/** Allowed persisted transfer states. The decision table deliberately rejects same-state transitions. */
public enum TransferState {
    COMPLETED,
    PENDING_SETTLEMENT,
    SETTLED,
    FAILED,
    RECONCILIATION_REQUIRED;

    public boolean allows(TransferState target) {
        if (target == null) return false;
        return switch (this) {
            case PENDING_SETTLEMENT -> target == SETTLED
                    || target == FAILED || target == RECONCILIATION_REQUIRED;
            case RECONCILIATION_REQUIRED -> target == SETTLED || target == FAILED;
            case COMPLETED, SETTLED, FAILED -> false;
        };
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == SETTLED || this == FAILED;
    }
}
