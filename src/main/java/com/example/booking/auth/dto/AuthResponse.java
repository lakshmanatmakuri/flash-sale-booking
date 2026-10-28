package com.example.booking.auth.dto;

import java.util.Set;
import java.util.UUID;

public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        UUID userId,
        String email,
        Set<String> roles
) {}