
package com.payrail.backend.api;

import com.payrail.backend.transfer.DemoClientTransferService;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

@RestController
@Profile("demo")
@RequestMapping("/api/client/transfers")
public class ClientTransferController {

    private final DemoClientTransferService transferService;

    public ClientTransferController(
            DemoClientTransferService transferService
    ) {
        this.transferService = transferService;
    }

    @PostMapping
    public Map<String, UUID> createTransfer(
            @Valid @RequestBody CreateTransferRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Principal principal
    ) {
        UUID transferId = transferService.createTransfer(
                request.senderAccountId(),
                request.receiverAccountId(),
                request.amountMinor(),
                idempotencyKey,
                principal
        );

        return Map.of("transferId", transferId);
    }
}
