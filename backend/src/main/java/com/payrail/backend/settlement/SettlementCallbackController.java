package com.payrail.backend.settlement;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HMAC is required: this endpoint is deliberately not protected by demo client credentials. */
@RestController
@RequestMapping("/api/settlement/callbacks")
public class SettlementCallbackController {
    private final SettlementCallbackAuthenticator authenticator;
    private final SettlementCallbackService service;

    public SettlementCallbackController(SettlementCallbackAuthenticator authenticator,
                                        SettlementCallbackService service) {
        this.authenticator = authenticator;
        this.service = service;
    }

    @PostMapping
    public SettlementCallbackService.Result receive(
            @Valid @RequestBody SettlementCallbackEvent event,
            @RequestHeader(value = "X-PayRail-Signature", required = false) String signature) {
        authenticator.verify(event, signature);
        return service.accept(event);
    }
}
