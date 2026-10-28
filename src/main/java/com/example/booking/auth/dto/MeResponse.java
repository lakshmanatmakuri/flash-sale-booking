package com.example.booking.auth.dto;

import java.util.Set;
import java.util.UUID;

public record MeResponse(
        UUID id,
        String email,
        Set<String> roles
) {}