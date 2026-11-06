
package com.payrail.backend.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record CreateTransferRequest(
        @NotNull UUID senderAccountId,
        @NotNull UUID receiverAccountId,
        @Positive long amountMinor
) {
}
