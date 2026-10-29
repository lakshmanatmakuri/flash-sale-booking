package com.example.booking.hold.dto;

import com.example.booking.hold.entity.HoldStatus;
import com.example.booking.hold.entity.SeatHold;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record HoldResponse(UUID id, UUID eventSeatId, UUID eventId, HoldStatus status,
                           BigDecimal lockedPrice, String currency, Instant expiresAt, int renewalCount) {

    public static HoldResponse from(SeatHold h) {
        return new HoldResponse(h.getId(), h.getEventSeatId(), h.getEventId(), h.getStatus(),
                h.getLockedPrice(), h.getCurrency(), h.getExpiresAt(), h.getRenewalCount());
    }
}