package com.example.booking.event.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreateEventRequest(
        @NotNull UUID venueId,
        @NotBlank @Size(max = 200) String name,
        @Size(max = 5000) String description,
        @NotNull @Future Instant startsAt,
        @NotNull Instant saleStartsAt,
        @NotNull Instant saleEndsAt,
        @Min(1) @Max(10) Integer maxSeatsPerUser,     // optional, defaults to 4
        @NotNull @DecimalMin("0.00") @Digits(integer = 15, fraction = 2) BigDecimal basePrice) {}
