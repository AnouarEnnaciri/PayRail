package com.payrail.simulator;

import java.time.Instant;
import java.util.UUID;

public record SettlementResponse(
        UUID settlementRequestId,
        long amountMinor,
        String destinationReference,
        SettlementStatus status,
        String externalReference,
        Instant createdAt,
        Instant updatedAt
) { }
