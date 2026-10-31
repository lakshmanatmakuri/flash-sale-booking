package com.example.booking.payment.service;

import com.example.booking.booking.service.BookingService;
import com.example.booking.event.service.EventService;
import com.example.booking.infrastructure.outbox.DomainEvents;
import com.example.booking.infrastructure.outbox.OutboxService;
import com.example.booking.ledger.service.LedgerPostings;
import com.example.booking.payment.entity.Payment;
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
    private final EventService eventService;
    private final LedgerPostings ledger;
    private final OutboxService outbox;
    /**
     * The guarded flip decides who settles. The booking, the seats AND the ledger entries commit together with it:
     * if any ledger rule fails, the flip rolls back too and the payment stays unresolved for recovery to retry.
     */
    @Transactional
    public boolean settleSuccess(UUID paymentId, String providerRef) {
        Optional<SettledPayment> won = dao.markSucceeded(paymentId, providerRef);
        if (won.isEmpty()) return false;
        SettledPayment p = won.get();

        UUID eventId = bookingService.confirm(p.bookingId());
        if (Payment.WALLET_PROVIDER.equals(p.provider())) {
            ledger.recordWalletCapture(p.paymentId(), p.userId(), p.amount(), p.currency());
        } else {
            ledger.recordCardCapture(p.paymentId(), p.amount(), p.currency());
        }
        ledger.recordBookingSettlement(p.bookingId(), eventService.organizerIdOf(eventId), p.amount(), p.currency());

        outbox.record("BOOKING", p.bookingId().toString(), DomainEvents.BOOKING_CONFIRMED,
                new DomainEvents.BookingConfirmed(p.bookingId(), eventId, p.userId(), p.paymentId(),
                        p.amount(), p.currency()));
        return true;
    }

    @Transactional
    public boolean settleFailure(UUID paymentId, String providerRef, String failureCode) {
        Optional<SettledPayment> won = dao.markFailed(paymentId, providerRef, failureCode);
        if (won.isEmpty()) return false;
        SettledPayment p = won.get();

        bookingService.fail(p.bookingId());          // emits one SeatReleased per seat; no money was captured
        outbox.record("PAYMENT", p.paymentId().toString(), DomainEvents.PAYMENT_FAILED,
                new DomainEvents.PaymentFailed(p.paymentId(), p.bookingId(), p.userId(), failureCode));
        return true;
    }

    @Transactional
    public void markUnknown(UUID paymentId, String providerRef) {
        dao.markUnknown(paymentId, providerRef);
    }
}