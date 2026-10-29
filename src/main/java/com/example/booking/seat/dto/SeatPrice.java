package com.example.booking.seat.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record SeatPrice(UUID eventSeatId, BigDecimal price, String currency) {}