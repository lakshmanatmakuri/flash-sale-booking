package com.example.booking.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

/**
 * The ONE error shape every endpoint returns.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        List<FieldViolation> violations) {

    public record FieldViolation(
            String field,
            String message) {
    }

    public static ApiError of(
            HttpStatus status,
            String code,
            String message,
            String path) {

        return new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                code,
                message,
                path,
                null
        );
    }
}