package com.example.booking.venue.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** One rectangular block of seats: rows are labelled A, B, C...; seats are numbered 1..seatsPerRow. */
public record SectionLayout(
        @NotBlank @Size(max = 30) String name,
        @Min(1) @Max(26) int rows,
        @Min(1) @Max(100) int seatsPerRow) {}