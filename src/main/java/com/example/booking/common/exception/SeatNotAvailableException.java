package com.example.booking.common.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class SeatNotAvailableException extends ApiException {
    public SeatNotAvailableException(UUID eventSeatId, Object currentStatus) {
        super(HttpStatus.CONFLICT, "SEAT_NOT_AVAILABLE",
                "Seat " + eventSeatId + " is not available (" + currentStatus + ")");
    }
}