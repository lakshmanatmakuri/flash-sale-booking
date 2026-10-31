package com.example.booking.common.exception;

import org.springframework.http.HttpStatus;

public class InsufficientBalanceException extends ApiException {
    public InsufficientBalanceException() {
        super(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_BALANCE", "Your wallet balance is too low for this payment");
    }
}