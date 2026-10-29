package com.example.booking.payment.dto;

import com.example.booking.payment.entity.Payment;
import com.example.booking.payment.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(UUID paymentId, UUID bookingId, PaymentStatus status, BigDecimal amount,
                              String currency, String failureCode, Instant createdAt) {

    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(p.getId(), p.getBookingId(), p.getStatus(), p.getAmount(), p.getCurrency(),
                p.getFailureCode(), p.getCreatedAt());
    }
}