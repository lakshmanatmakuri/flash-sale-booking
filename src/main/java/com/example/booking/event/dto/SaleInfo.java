package com.example.booking.event.dto;

import java.util.UUID;

/** What the hold module needs to know about an event, and nothing more. */
public record SaleInfo(UUID eventId, int maxSeatsPerUser) {}