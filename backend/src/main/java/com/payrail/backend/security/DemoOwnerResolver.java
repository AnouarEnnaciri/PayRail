package com.payrail.backend.security;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

@Component
@Profile("demo")
public class DemoOwnerResolver {

    private static final Map<String, UUID> OWNERS = Map.of(
            "client.alpha@example.test",
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),

            "client.beta@example.test",
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
    );

    public UUID resolve(Principal principal) {
        if (principal == null) {
            throw new AccessDeniedException("Authentication required");
        }

        UUID ownerId = OWNERS.get(principal.getName());

        if (ownerId == null) {
            throw new AccessDeniedException("Unknown client");
        }

        return ownerId;
    }
}