package com.example.booking.payment.service;

import com.example.booking.common.exception.ResourceNotFoundException;
import com.example.booking.payment.dto.PaymentResponse;
import com.example.booking.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentQueryService {

    private final PaymentRepository payments;

    @Transactional(readOnly = true)
    public PaymentResponse get(UUID paymentId) {
        return payments.findById(paymentId).map(PaymentResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
    }

    @Transactional(readOnly = true)
    public PaymentResponse getForUser(UUID userId, UUID paymentId) {
        return payments.findById(paymentId).filter(p -> p.getUserId().equals(userId)).map(PaymentResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
    }
}