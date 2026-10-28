package com.example.booking.common.exception;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String resource, Object id) {
        super(
                HttpStatus.NOT_FOUND,
                "NOT_FOUND",
                resource + " not found: " + id
        );
    }
}
