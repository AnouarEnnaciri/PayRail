
package com.payrail.backend.transfer;

import com.payrail.backend.account.AccountRepository;
import com.payrail.backend.security.DemoOwnerResolver;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.UUID;

@Service
@Profile("demo")
public class DemoClientTransferService {

    private final AccountRepository accountRepository;
    private final DemoOwnerResolver ownerResolver;
    private final IdempotentTransferService transferService;

    public DemoClientTransferService(
            AccountRepository accountRepository,
            DemoOwnerResolver ownerResolver,
            IdempotentTransferService transferService
    ) {
        this.accountRepository = accountRepository;
        this.ownerResolver = ownerResolver;
        this.transferService = transferService;
    }

    @Transactional
    public UUID createTransfer(
            UUID senderId,
            UUID receiverId,
            long amountMinor,
            String idempotencyKey,
            Principal principal
    ) {
        UUID ownerId = ownerResolver.resolve(principal);

        accountRepository.findByIdAndOwnerId(
                senderId, ownerId
        ).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Sender account not found"
        ));

        return transferService.transfer(
                senderId,
                receiverId,
                amountMinor,
                idempotencyKey
        );
    }
}
