package com.payrail.backend.api;

import java.security.Principal;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ClientController {

    @GetMapping("/api/client/me")
    public Map<String, String> me(Principal principal) {
        return Map.of("username", principal.getName());
    }
}