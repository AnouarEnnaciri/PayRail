package com.payrail.simulator;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record SubmitSettlementRequest(
        @NotNull UUID settlementRequestId,
        @Positive long amountMinor,
        @NotBlank @Size(max = 128) String destinationReference,
        @NotNull SettlementMode mode
) { }
