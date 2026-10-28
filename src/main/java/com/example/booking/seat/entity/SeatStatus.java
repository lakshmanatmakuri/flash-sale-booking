package com.example.booking.seat.entity;

/** The lifecycle states of an event seat. The allowed-transition rules and the guarded updates arrive in Phase 5. */
public enum SeatStatus { AVAILABLE, HELD, PAYMENT_PENDING, BOOKED, CANCELLED }
