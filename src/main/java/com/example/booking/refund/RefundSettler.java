package com.example.booking.refund;

import com.example.booking.infrastructure.outbox.DomainEvents;
import com.example.booking.infrastructure.outbox.OutboxService;
import com.example.booking.ledger.service.LedgerPostings;
import com.example.booking.payment.repository.PaymentDao;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefundSettler {

    private final RefundDao refunds;
    private final PaymentDao payments;
    private final LedgerPostings ledger;
    private final OutboxService outbox;

    /** Whoever flips the refund to SUCCEEDED also finishes the books, in the same transaction. Returns false for a duplicate. */
    @Transactional
    public boolean settleSuccess(UUID refundId, String providerRef) {
        Optional<RefundDao.Row> won = refunds.markSucceeded(refundId, providerRef);
        if (won.isEmpty()) return false;
        RefundDao.Row r = won.get();

        if (!payments.markRefunded(r.paymentId())) {
            throw new IllegalStateException("Payment " + r.paymentId() + " was not REFUND_PENDING at refund completion");
        }
        ledger.reversePaymentCapture(r.paymentId(), r.id());
        outbox.record("PAYMENT", r.paymentId().toString(), DomainEvents.REFUND_COMPLETED,
                new DomainEvents.RefundCompleted(r.id(), r.paymentId(), r.bookingId(), r.userId(),
                        r.amount(), r.currency(), r.reason()));
        return true;
    }

    /** The customer is still owed this money: the ledger keeps it in escrow, and the event + ERROR log make it visible. */
    @Transactional
    public void settleFailure(UUID refundId, String failureCode) {
        refunds.markFailed(refundId, failureCode).ifPresent(r -> {
            log.error("Refund {} for payment {} FAILED (attempt {}): {}", r.id(), r.paymentId(), r.attempts(), failureCode);
            outbox.record("PAYMENT", r.paymentId().toString(), DomainEvents.REFUND_FAILED,
                    new DomainEvents.RefundFailed(r.id(), r.paymentId(), r.bookingId(), r.userId(), failureCode, r.attempts()));
        });
    }

    @Transactional
    public void markUnknown(UUID refundId) {
        refunds.markUnknown(refundId);
    }
}