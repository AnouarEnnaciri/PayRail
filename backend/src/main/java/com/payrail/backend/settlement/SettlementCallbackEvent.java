package com.payrail.backend.settlement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Signed, terminal observation from the simulated external settlement participant. */
public record SettlementCallbackEvent(
        @NotNull UUID eventId,
        @NotNull UUID settlementRequestId,
        @Positive long amountMinor,
        @NotBlank @Size(max = 128) String destinationReference,
        @NotBlank @Size(max = 16) String status,
        @Size(max = 128) String externalReference
) { }
