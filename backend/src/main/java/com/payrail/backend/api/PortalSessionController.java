package com.payrail.backend.api;

import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated session metadata for the React demo. Credentials are never returned. */
@RestController
@Profile("demo")
public class PortalSessionController {
    public record SessionView(String username, String role, String csrfHeaderName, String csrfToken) { }

    @GetMapping("/api/ui/session")
    public ResponseEntity<SessionView> session(Authentication authentication, CsrfToken csrfToken) {
        String role = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_OPS".equals(authority.getAuthority())) ? "OPS" : "CLIENT";
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new SessionView(authentication.getName(), role,
                        csrfToken.getHeaderName(), csrfToken.getToken()));
    }
}
