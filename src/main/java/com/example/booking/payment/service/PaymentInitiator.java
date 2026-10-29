package com.example.booking.payment.service;

import com.example.booking.booking.entity.Booking;
import com.example.booking.booking.service.BookingService;
import com.example.booking.common.idempotency.IdempotencyService;
import com.example.booking.hold.dto.ConvertedHold;
import com.example.booking.hold.service.HoldService;
import com.example.booking.payment.dto.PayRequest;
import com.example.booking.payment.entity.Payment;
import com.example.booking.payment.provider.PaymentProvider;
import com.example.booking.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A separate bean from PaymentService so its @Transactional is applied through the Spring proxy (never self-invoked). */
@Service
@RequiredArgsConstructor
public class PaymentInitiator {

    public static final String SCOPE = "PAYMENT_CREATE";

    public record Initiation(UUID paymentId, boolean replayed, BigDecimal amount, String currency) {}

    private final IdempotencyService idempotency;
    private final HoldService holdService;
    private final BookingService bookingService;
    private final PaymentRepository payments;
    private final PaymentProvider provider;

    /** TX 1: short, database only. Nothing in here may talk to the network. */
    @Transactional
    public Initiation initiate(UUID userId, String idempotencyKey, PayRequest req) {
        var claim = idempotency.claim(userId, SCOPE, idempotencyKey, req);
        if (claim.replay()) {
            return new Initiation(claim.resourceId(), true, null, null);
        }

        List<ConvertedHold> holds = holdService.convertForCheckout(userId, req.holdIds());
        Booking booking = bookingService.createPending(userId, holds);
        Payment payment = payments.saveAndFlush(Payment.pending(
                booking.getId(), userId, booking.getTotalAmount(), booking.getCurrency(), provider.name()));
        idempotency.attachResource(userId, SCOPE, idempotencyKey, payment.getId());
        return new Initiation(payment.getId(), false, payment.getAmount(), payment.getCurrency());
    }
}