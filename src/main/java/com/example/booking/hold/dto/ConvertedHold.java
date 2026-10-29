package com.example.booking.hold.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** A hold that has just become part of a booking. Carries the price that was frozen at hold time. */
public record ConvertedHold(UUID holdId, UUID eventSeatId, UUID eventId, BigDecimal price, String currency) {}