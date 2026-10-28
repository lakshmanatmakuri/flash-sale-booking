package com.example.booking.common.exception;

import org.springframework.http.HttpStatus;

public class AccountNotActiveException extends ApiException {

    public AccountNotActiveException() {
        super(
                HttpStatus.FORBIDDEN,
                "ACCOUNT_NOT_ACTIVE",
                "This account is not active"
        );
    }
}