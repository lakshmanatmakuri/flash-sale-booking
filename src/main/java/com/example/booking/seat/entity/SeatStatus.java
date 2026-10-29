package com.example.booking.seat.entity;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The seat lifecycle. Allowed transitions live here; the SQL guards (WHERE status = :from) enforce them under concurrency. */
public enum SeatStatus {
    AVAILABLE, HELD, PAYMENT_PENDING, BOOKED, CANCELLED;

    private static final Map<SeatStatus, Set<SeatStatus>> ALLOWED = new EnumMap<>(SeatStatus.class);

    static {
        ALLOWED.put(AVAILABLE,       EnumSet.of(HELD));
        ALLOWED.put(HELD,            EnumSet.of(AVAILABLE, PAYMENT_PENDING));
        ALLOWED.put(PAYMENT_PENDING, EnumSet.of(BOOKED, AVAILABLE));
        ALLOWED.put(BOOKED,          EnumSet.of(CANCELLED));
        ALLOWED.put(CANCELLED,       EnumSet.of(AVAILABLE));
    }

    public boolean canTransitionTo(SeatStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    /** A bug guard: asking for an illegal transition is a programming error, not a user error. */
    public void requireTransitionTo(SeatStatus next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException("Illegal seat transition " + this + " -> " + next);
        }
    }
}