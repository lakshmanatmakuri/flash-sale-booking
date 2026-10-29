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
        // enableSuppression=false, writableStackTrace=false: these are EXPECTED business outcomes
        // (in a flash sale, 9,999 of 10,000 requests for one seat end here). Capturing a stack trace
        // for each one is pure CPU waste, and nobody reads it.
        super(message, null, false, false);
        this.status = status;
        this.code = code;
    }
//    public ApiException(HttpStatus status, String code, String message) {
//        super(message);
//        this.status = status;
//        this.code = code;
//    }
}
