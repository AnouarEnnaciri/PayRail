package com.payrail.simulator;

import java.time.Instant;
import java.util.UUID;

record SettlementRecord(
        UUID settlementRequestId,
        String requestHash,
        long amountMinor,
        String destinationReference,
        SettlementMode mode,
        SettlementStatus status,
        String externalReference,
        Instant createdAt,
        Instant updatedAt
) {
    SettlementResponse response() {
        return new SettlementResponse(
                settlementRequestId, amountMinor, destinationReference,
                status, externalReference, createdAt, updatedAt
        );
    }
}
