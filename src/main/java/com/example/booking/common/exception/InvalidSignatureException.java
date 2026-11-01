package com.example.booking.common.exception;

import org.springframework.http.HttpStatus;

public class InvalidSignatureException extends ApiException {
    public InvalidSignatureException() {
        super(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Webhook signature is missing, invalid or expired");
    }
}
