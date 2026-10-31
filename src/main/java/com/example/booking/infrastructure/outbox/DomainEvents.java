package com.example.booking.infrastructure.outbox;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The event vocabulary. Note: `eventId` always means the concert EVENT; the message id is the outbox row id. */
public final class DomainEvents {
    private DomainEvents() {}

    public static final String SEAT_HELD = "SeatHeld";
    public static final String SEAT_HOLD_EXPIRED = "SeatHoldExpired";
    public static final String SEAT_RELEASED = "SeatReleased";
    public static final String PAYMENT_FAILED = "PaymentFailed";
    public static final String BOOKING_CONFIRMED = "BookingConfirmed";

    public record SeatHeld(UUID eventId, UUID eventSeatId, UUID holdId, UUID userId,
                           BigDecimal lockedPrice, Instant expiresAt) {}
    public record SeatHoldExpired(UUID eventId, UUID eventSeatId, UUID holdId) {}
    /** reason: HOLD_RELEASED | HOLD_EXPIRED | PAYMENT_FAILED */
    public record SeatReleased(UUID eventId, UUID eventSeatId, String reason) {}
    public record PaymentFailed(UUID paymentId, UUID bookingId, UUID userId, String failureCode) {}
    public record BookingConfirmed(UUID bookingId, UUID eventId, UUID userId, UUID paymentId,
                                   BigDecimal amount, String currency) {}
}