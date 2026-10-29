package com.example.booking.payment.dto;

/** `replayed` = this response came from an earlier request with the same Idempotency-Key. */
public record PaymentResult(PaymentResponse payment, boolean replayed) {}
