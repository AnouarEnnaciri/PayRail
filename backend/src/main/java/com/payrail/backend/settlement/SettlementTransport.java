package com.payrail.backend.settlement;

import java.util.UUID;

/** The external participant contract. Results describe external evidence, not a PayRail financial effect. */
public interface SettlementTransport {

    record Command(UUID settlementRequestId, long amountMinor,
                   String destinationReference, String mode) { }

    // Match the full M6 simulator JSON contract; timestamps are evidence metadata.
    record Receipt(UUID settlementRequestId, long amountMinor,
                   String destinationReference, String status,
                   String externalReference, String createdAt, String updatedAt) {
        public Receipt(UUID settlementRequestId, long amountMinor,
                       String destinationReference, String status, String externalReference) {
            this(settlementRequestId, amountMinor, destinationReference,
                    status, externalReference, null, null);
        }
    }

    Receipt submit(Command request);

    Receipt get(UUID settlementRequestId);
}
