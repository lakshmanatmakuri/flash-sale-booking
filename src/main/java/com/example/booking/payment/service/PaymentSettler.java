package com.example.booking.payment.service;

import com.example.booking.booking.service.BookingService;
import com.example.booking.payment.repository.PaymentDao;
import com.example.booking.payment.repository.PaymentDao.SettledPayment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentSettler {

    private final PaymentDao dao;
    private final BookingService bookingService;

    /**
     * The guarded flip decides who settles. Everything after it commits together with the flip, so a failure
     * halfway rolls the flip back and the payment stays unresolved for recovery to retry.
     * Returns false if somebody else already settled this payment (a harmless no-op).
     */
    @Transactional
    public boolean settleSuccess(UUID paymentId, String providerRef) {
        Optional<SettledPayment> won = dao.markSucceeded(paymentId, providerRef);
        if (won.isEmpty()) return false;
        bookingService.confirm(won.get().bookingId());
        // PHASE 7: ledger entries.  PHASE 8: BookingConfirmed outbox event.
        return true;
    }

    @Transactional
    public boolean settleFailure(UUID paymentId, String providerRef, String failureCode) {
        Optional<SettledPayment> won = dao.markFailed(paymentId, providerRef, failureCode);
        if (won.isEmpty()) return false;
        bookingService.fail(won.get().bookingId());
        // PHASE 8: PaymentFailed + SeatReleased outbox events.
        return true;
    }

    @Transactional
    public void markUnknown(UUID paymentId, String providerRef) {
        dao.markUnknown(paymentId, providerRef);
    }
}