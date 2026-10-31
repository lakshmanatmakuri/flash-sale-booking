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
    private final PaymentSettler settler;

    /** Card, TX 1: short, database only. The provider call happens afterwards, outside any transaction. */
    @Transactional
    public Initiation initiate(UUID userId, String idempotencyKey, PayRequest req) {
        var claim = idempotency.claim(userId, SCOPE, idempotencyKey, req);
        if (claim.replay()) return new Initiation(claim.resourceId(), true, null, null);
        Payment payment = createPending(userId, idempotencyKey, req, provider.name());
        return new Initiation(payment.getId(), false, payment.getAmount(), payment.getCurrency());
    }

    /**
     * Wallet: ONE transaction, start to finish. There is no network call, so nothing slow can happen while locks are
     * held. If the wallet is short, InsufficientBalanceException rolls back everything: the holds stay ACTIVE, the
     * idempotency key is released, and the user can top up and retry with the same request.
     */
    @Transactional
    public Initiation payWithWallet(UUID userId, String idempotencyKey, PayRequest req) {
        var claim = idempotency.claim(userId, SCOPE, idempotencyKey, req);
        if (claim.replay()) return new Initiation(claim.resourceId(), true, null, null);
        Payment payment = createPending(userId, idempotencyKey, req, Payment.WALLET_PROVIDER);
        settler.settleSuccess(payment.getId(), null);     // joins this transaction: debit wallet, confirm, settle
        return new Initiation(payment.getId(), false, payment.getAmount(), payment.getCurrency());
    }

    private Payment createPending(UUID userId, String idempotencyKey, PayRequest req, String providerName) {
        List<ConvertedHold> holds = holdService.convertForCheckout(userId, req.holdIds());
        Booking booking = bookingService.createPending(userId, holds);
        Payment payment = payments.saveAndFlush(Payment.pending(
                booking.getId(), userId, booking.getTotalAmount(), booking.getCurrency(), providerName));
        idempotency.attachResource(userId, SCOPE, idempotencyKey, payment.getId());
        return payment;
    }
}