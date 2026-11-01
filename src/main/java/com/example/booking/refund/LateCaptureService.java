package com.example.booking.refund;

import com.example.booking.ledger.service.LedgerPostings;
import com.example.booking.payment.repository.PaymentDao;
import com.example.booking.payment.repository.PaymentDao.SettledPayment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LateCaptureService {

    private final PaymentDao payments;
    private final RefundDao refunds;
    private final LedgerPostings ledger;

    /**
     * We had already failed this payment and released the seat, but the provider reports it captured the money.
     * The seat may be resold, so we do NOT re-book. We record the truth (the money is with us), then owe it back.
     * Joins the caller's (webhook) transaction. Returns the refund to execute AFTER that transaction commits.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> compensate(UUID paymentId, String providerRef) {
        Optional<SettledPayment> late = payments.markLateCaptured(paymentId, providerRef);   // FAILED -> REFUND_PENDING
        if (late.isEmpty()) return Optional.empty();                                          // someone already handled it
        SettledPayment p = late.get();

        ledger.recordCardCapture(p.paymentId(), p.amount(), p.currency());   // DR PROVIDER_CLEARING / CR ESCROW: record reality first
        return Optional.of(refunds.insert(p.paymentId(), p.bookingId(), p.userId(), p.amount(), p.currency(),
                "LATE_PAYMENT_SUCCESS"));
    }
}
