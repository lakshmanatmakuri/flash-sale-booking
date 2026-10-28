package com.example.booking.seat.dto;

import com.example.booking.seat.entity.SeatStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record SeatMapItem(UUID eventSeatId, String section, String row, Integer seatNumber,
                          SeatStatus status, BigDecimal price) {}
