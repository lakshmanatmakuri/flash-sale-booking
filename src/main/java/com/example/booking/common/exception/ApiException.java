package com.example.booking.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base class for every error we deliberately raise.
 * Carries the HTTP status and a stable machine code.
 */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
