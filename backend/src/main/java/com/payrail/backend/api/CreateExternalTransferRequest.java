package com.payrail.backend.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateExternalTransferRequest(
        @NotNull UUID senderAccountId,
        @NotBlank @Size(max = 128) String destinationReference,
        @Positive long amountMinor
) { }
