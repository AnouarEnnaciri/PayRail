package com.payrail.backend.api;

import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Returns the session CSRF token for authenticated demo clients; CSRF remains enabled. */
@RestController
@Profile("demo")
@RequestMapping("/api/client")
public class ClientCsrfController {

    @GetMapping("/csrf")
    public ResponseEntity<CsrfToken> token(CsrfToken csrfToken) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(csrfToken);
    }
}
