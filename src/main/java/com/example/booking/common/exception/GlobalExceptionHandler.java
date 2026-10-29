package com.example.booking.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApi(
            ApiException ex,
            HttpServletRequest req) {

        return build(
                ex.getStatus(),
                ex.getCode(),
                ex.getMessage(),
                req,
                null
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest req) {

        List<ApiError.FieldViolation> violations =
                ex.getBindingResult()
                        .getFieldErrors()
                        .stream()
                        .map(f -> new ApiError.FieldViolation(
                                f.getField(),
                                f.getDefaultMessage()))
                        .toList();

        return build(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Request validation failed",
                req,
                violations
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadable(
            HttpMessageNotReadableException ex,
            HttpServletRequest req) {

        return build(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST",
                "Request body is missing or malformed",
                req,
                null
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest req) {

        return build(
                HttpStatus.BAD_REQUEST,
                "INVALID_PARAMETER",
                "Invalid value for parameter '" + ex.getName() + "'",
                req,
                null
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleDenied(
            AccessDeniedException ex,
            HttpServletRequest req) {

        return build(
                HttpStatus.FORBIDDEN,
                "ACCESS_DENIED",
                "You do not have permission to do this",
                req,
                null
        );
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleIntegrity(
            DataIntegrityViolationException ex,
            HttpServletRequest req) {

        log.warn(
                "Data integrity violation on {} {}: {}",
                req.getMethod(),
                req.getRequestURI(),
                ex.getMostSpecificCause().getMessage()
        );

        return build(
                HttpStatus.CONFLICT,
                "DATA_INTEGRITY_VIOLATION",
                "The request conflicts with existing data",
                req,
                null
        );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleAny(
            Exception ex,
            HttpServletRequest req) {

        if (ex instanceof org.springframework.web.ErrorResponse er) {

            HttpStatus status =
                    HttpStatus.valueOf(er.getStatusCode().value());

            return build(
                    status,
                    status.name(),
                    status.getReasonPhrase(),
                    req,
                    null
            );
        }

        log.error(
                "Unhandled exception on {} {}",
                req.getMethod(),
                req.getRequestURI(),
                ex
        );

        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "An unexpected error occurred",
                req,
                null
        );
    }

    private ResponseEntity<ApiError> build(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest req,
            List<ApiError.FieldViolation> violations) {

        return ResponseEntity
                .status(status)
                .body(new ApiError(
                        java.time.Instant.now(),
                        status.value(),
                        status.getReasonPhrase(),
                        code,
                        message,
                        req.getRequestURI(),
                        violations
                ));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "MISSING_HEADER",
                "Required header '" + ex.getHeaderName() + "' is missing", req, null);
    }
}