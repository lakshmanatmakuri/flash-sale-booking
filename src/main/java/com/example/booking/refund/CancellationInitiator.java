package com.example.booking.refund;

import com.example.booking.booking.entity.Booking;
import com.example.booking.booking.entity.BookingStatus;
import com.example.booking.booking.repository.BookingRepository;
import com.example.booking.booking.service.BookingService;
import com.example.booking.common.exception.ConflictException;
import com.example.booking.common.exception.ResourceNotFoundException;
import com.example.booking.event.service.EventService;
import com.example.booking.ledger.service.LedgerPostings;
import com.example.booking.payment.entity.Payment;
import com.example.booking.payment.repository.PaymentDao;
import com.example.booking.payment.repository.PaymentDao.SettledPayment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CancellationInitiator {

    public record Begun(UUID refundId, boolean replayed, boolean needsProviderCall) {}

    private final BookingRepository bookings;
    private final BookingService bookingService;
    private final EventService eventService;
    private final PaymentDao payments;
    private final RefundDao refunds;
    private final RefundSettler refundSettler;
    private final LedgerPostings ledger;

    /** TX 1: everything that is purely ours, atomically. Nothing here talks to the provider. */
    @Transactional
    public Begun begin(UUID userId, UUID bookingId) {
        Booking booking = bookings.findById(bookingId).filter(b -> b.getUserId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));      // someone else's = 404

        if (booking.getStatus() == BookingStatus.CANCELLED) return replay(bookingId);        // idempotent by state
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new ConflictException("BOOKING_NOT_CANCELLABLE",
                    "Only confirmed bookings can be cancelled (this one is " + booking.getStatus() + ")");
        }
        eventService.requireNotStarted(booking.getEventId());

        if (bookingService.cancel(bookingId, userId).isEmpty()) {
            return replay(bookingId);       // a concurrent cancel won the guarded flip; its refund row is committed by now
        }
        SettledPayment payment = payments.startRefundForBooking(bookingId).orElseThrow(() ->
                new IllegalStateException("Confirmed booking " + bookingId + " has no SUCCEEDED payment"));
        UUID refundId = refunds.insert(payment.paymentId(), bookingId, userId, payment.amount(), payment.currency(),
                "BOOKING_CANCELLED");

        ledger.reverseBookingSettlement(bookingId, refundId);     // the organizer's share and the fee come back NOW

        if (Payment.WALLET_PROVIDER.equals(payment.provider())) {
            refundSettler.settleSuccess(refundId, null);          // wallet money: a pure ledger move, done in this transaction
            return new Begun(refundId, false, false);
        }
        return new Begun(refundId, false, true);                  // card money: the provider call happens after commit
    }

    private Begun replay(UUID bookingId) {
        UUID refundId = refunds.findByBooking(bookingId).map(RefundDao.Row::id).orElseThrow(() ->
                new IllegalStateException("Cancelled booking " + bookingId + " has no refund"));
        return new Begun(refundId, true, false);
    }
}