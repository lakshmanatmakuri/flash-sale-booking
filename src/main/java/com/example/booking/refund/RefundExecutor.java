package com.example.booking.refund;

import com.example.booking.payment.PaymentProperties;
import com.example.booking.payment.provider.PaymentProvider;
import com.example.booking.payment.provider.PaymentProvider.RefundRequest;
import com.example.booking.payment.provider.PaymentProvider.RefundResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefundExecutor {

    private final RefundDao refunds;
    private final PaymentProvider provider;
    private final RefundSettler settler;
    private final PaymentProperties props;

    /**
     * Safe to call any number of times, from any instance: the provider is idempotent on the refund id, and the settle
     * steps are guarded flips. Used for the first attempt, for retries, and for crash recovery.
     * NOT @Transactional: the provider call must never run inside a database transaction.
     */
    public void execute(UUID refundId) {
        Optional<RefundDao.Row> row = refunds.beginAttempt(refundId);    // commits immediately; empty = already done
        if (row.isEmpty()) return;
        RefundDao.Row r = row.get();

        RefundResult result = refundSafely(r);
        switch (result.outcome()) {
            case SUCCEEDED -> settler.settleSuccess(r.id(), result.providerRef());
            case FAILED -> settler.settleFailure(r.id(), result.failureCode());
            case TIMEOUT, UNKNOWN -> settler.markUnknown(r.id());        // we do not know: never assume either way
        }
    }

    /** Returns how many refunds were attempted. */
    public int recoverStuck() {
        int attempted = 0;
        for (UUID id : refunds.findRecoverable(props.recoveryMinAgeSeconds(), props.refundMaxAttempts(), props.recoveryBatchSize())) {
            try {
                execute(id);
                attempted++;
            } catch (RuntimeException e) {
                log.error("Refund recovery failed for {}", id, e);       // one bad refund must not block the others
            }
        }
        return attempted;
    }

    private RefundResult refundSafely(RefundDao.Row r) {
        try {
            return provider.refund(new RefundRequest(r.id().toString(), r.paymentId().toString(), r.amount(), r.currency()));
        } catch (RuntimeException e) {
            log.warn("Provider refund call failed for refund {} ({})", r.id(), e.getClass().getSimpleName());
            return RefundResult.timeout();
        }
    }
}