package com.example.booking.auth.security;

import java.util.Set;
import java.util.UUID;

/**
 * The principal placed in the SecurityContext after a valid JWT.
 * Controllers receive it via @AuthenticationPrincipal.
 */
public record AuthenticatedUser(
        UUID id,
        String email,
        Set<String> roles) {

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean isAdmin() {
        return hasRole("ADMIN");
    }
}