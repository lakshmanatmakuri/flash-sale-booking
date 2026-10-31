package com.example.booking.wallet.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record TopUpRequest(
        @NotNull @DecimalMin("1.00") @DecimalMax("100000.00") @Digits(integer = 6, fraction = 2) BigDecimal amount,
        @NotBlank @Size(max = 100) String paymentMethodToken) {}