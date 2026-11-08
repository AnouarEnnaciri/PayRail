package com.payrail.backend.api;

import com.payrail.backend.account.AccountRepository;
import com.payrail.backend.security.DemoOwnerResolver;
import com.payrail.backend.transfer.ExternalReservationService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;

@RestController
@Profile("demo")
@RequestMapping("/api/client/external-transfers")
public class ClientExternalTransferController {
    private final AccountRepository accounts;
    private final DemoOwnerResolver ownerResolver;
    private final ExternalReservationService reservation;

    public ClientExternalTransferController(AccountRepository accounts,
                                            DemoOwnerResolver ownerResolver,
                                            ExternalReservationService reservation) {
        this.accounts = accounts;
        this.ownerResolver = ownerResolver;
        this.reservation = reservation;
    }

    @PostMapping
    public ExternalReservationService.ReservationResult create(
            @Valid @RequestBody CreateExternalTransferRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Principal principal) {
        var ownerId = ownerResolver.resolve(principal);
        accounts.findByIdAndOwnerId(request.senderAccountId(), ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Sender account not found"));
        return reservation.reserve(request.senderAccountId(), request.destinationReference(),
                request.amountMinor(), idempotencyKey);
    }
}
