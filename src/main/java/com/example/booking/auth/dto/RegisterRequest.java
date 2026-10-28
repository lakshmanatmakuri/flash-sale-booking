package com.example.booking.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
        @Email
        @Size(max = 255)
        String email,

        @NotBlank
        @Size(min = 8, max = 72, message = "must be 8 to 72 characters")
        String password,

        @NotBlank
        @Size(max = 150)
        String fullName
) {}