package com.example.booking.venue.dto;

import java.util.UUID;

public record VenueResponse(UUID id, String name, String city, int totalSeats) {}