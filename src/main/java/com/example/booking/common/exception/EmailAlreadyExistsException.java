package com.example.booking.common.exception;

import org.springframework.http.HttpStatus;

public class EmailAlreadyExistsException extends ApiException {

    public EmailAlreadyExistsException() {
        super(
                HttpStatus.CONFLICT,
                "EMAIL_ALREADY_EXISTS",
                "An account with this email already exists"
        );
    }
}