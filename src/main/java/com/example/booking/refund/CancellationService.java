package com.example.booking.refund;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CancellationService {

    private final CancellationInitiator initiator;
    private final RefundExecutor executor;
    private final RefundDao refunds;

    /** TX 1 (seat, booking, payment, refund row, ledger), then the provider refund with no transaction open. */
    public CancellationResponse cancel(UUID userId, UUID bookingId) {
        CancellationInitiator.Begun begun = initiator.begin(userId, bookingId);
        if (begun.needsProviderCall()) {
            try {
                executor.execute(begun.refundId());
            } catch (RuntimeException e) {
                // The booking IS cancelled either way. The refund row is durable, and the recovery job will finish it.
                log.error("Refund {} could not be completed now; recovery will retry", begun.refundId(), e);
            }
        }
        RefundDao.Row r = refunds.find(begun.refundId()).orElseThrow();
        return new CancellationResponse(bookingId, "CANCELLED", r.id(), r.status(), r.amount(), r.currency(), begun.replayed());
    }
}