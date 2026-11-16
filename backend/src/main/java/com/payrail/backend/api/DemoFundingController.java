package com.payrail.backend.api;

import com.payrail.backend.ledger.DemoFundingService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Available only with the demo Spring profile; /api/ops/** is protected by ROLE_OPS. */
@RestController
@Profile("demo")
@RequestMapping("/api/ops/demo-funding")
public class DemoFundingController {
    public record FundingRequest(String accountReference, Long amountMinor) { }
    private final DemoFundingService funding;

    public DemoFundingController(DemoFundingService funding) { this.funding = funding; }

    @GetMapping("/accounts")
    public List<DemoFundingService.DemoAccount> accounts() {
        return funding.accounts();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DemoFundingService.FundingResult fund(@RequestBody FundingRequest request,
            @RequestHeader("Idempotency-Key") UUID requestId, Authentication authentication) {
        return funding.fund(request.accountReference(), request.amountMinor(), requestId, authentication.getName());
    }
}
