package com.example.booking.common.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class HoldExpiredException extends ApiException {
    public HoldExpiredException(UUID holdId) {
        super(HttpStatus.CONFLICT, "HOLD_EXPIRED", "Hold " + holdId + " is no longer active or has expired");
    }
}
