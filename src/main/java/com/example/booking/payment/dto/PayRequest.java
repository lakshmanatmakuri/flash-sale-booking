package com.example.booking.payment.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record PayRequest(
        @NotEmpty @Size(max = 10) List<@NotNull UUID> holdIds,
        @NotNull PaymentMethod method,
        @Size(max = 100) String paymentMethodToken) {}   // never logged, never stored