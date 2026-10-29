package com.example.booking.payment.service;

import com.example.booking.common.exception.BadRequestException;
import com.example.booking.payment.PaymentProperties;
import com.example.booking.payment.dto.PayRequest;
import com.example.booking.payment.dto.PaymentMethod;
import com.example.booking.payment.dto.PaymentResult;
import com.example.booking.payment.provider.PaymentProvider;
import com.example.booking.payment.provider.PaymentProvider.ChargeRequest;
import com.example.booking.payment.provider.PaymentProvider.ChargeResult;
import com.example.booking.payment.provider.PaymentProvider.ProviderStatus;
import com.example.booking.payment.repository.PaymentDao;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentInitiator initiator;
    private final PaymentSettler settler;
    private final PaymentQueryService queries;
    private final PaymentProvider provider;
    private final PaymentDao dao;
    private final PaymentProperties props;

    /**
     * NOT @Transactional: it consists of two separate transactions (initiate, settle) with a network call between
     * them, and no database connection is held while we wait for the provider.
     */
    public PaymentResult pay(UUID userId, String idempotencyKey, PayRequest req) {
        if (req.method() == PaymentMethod.CARD
                && (req.paymentMethodToken() == null || req.paymentMethodToken().isBlank())) {
            throw new BadRequestException("paymentMethodToken is required for CARD payments");
        }

        PaymentInitiator.Initiation init = initiator.initiate(userId, idempotencyKey, req);   // TX 1
        if (init.replayed()) {
            // Duplicate request: do NOTHING again, just report where the original payment stands right now.
            return new PaymentResult(queries.get(init.paymentId()), true);
        }

        ChargeResult result = chargeSafely(init, req);                                         // no transaction

        switch (result.outcome()) {                                                            // TX 2
            case SUCCEEDED -> settler.settleSuccess(init.paymentId(), result.providerRef());
            case FAILED -> settler.settleFailure(init.paymentId(), result.providerRef(), result.failureCode());
            case TIMEOUT, UNKNOWN -> settler.markUnknown(init.paymentId(), result.providerRef());
        }
        return new PaymentResult(queries.get(init.paymentId()), false);
    }

    /**
     * Resolves payments whose outcome we lost: the server crashed between charge and settle, the response was
     * lost, the provider said "processing". Asks the provider (idempotency reference = payment id), then settles.
     * Safe on every app instance at once: settlement is a guarded flip, so exactly one caller wins.
     */
    public int recoverStuckPayments() {
        int resolved = 0;
        for (UUID id : dao.findStuck(props.recoveryMinAgeSeconds(), props.recoveryBatchSize())) {
            try {
                if (recoverOne(id)) resolved++;
            } catch (RuntimeException e) {
                log.error("Recovery failed for payment {}", id, e);   // one bad payment must not block the others
            }
        }
        return resolved;
    }

    private boolean recoverOne(UUID paymentId) {
        ProviderStatus status = provider.queryStatus(paymentId.toString());   // outside any transaction
        return switch (status) {
            case SUCCEEDED -> settler.settleSuccess(paymentId, null);
            case FAILED -> settler.settleFailure(paymentId, null, "provider_failed");
            // Safe only because we waited recovery-min-age (> provider timeout): by now an in-flight request is dead.
            case NOT_FOUND -> settler.settleFailure(paymentId, null, "never_reached_provider");
            case PENDING -> false;                                              // still processing: look again later
        };
    }

    private ChargeResult chargeSafely(PaymentInitiator.Initiation init, PayRequest req) {
        try {
            return provider.charge(new ChargeRequest(init.paymentId().toString(), init.amount(),
                    init.currency(), req.paymentMethodToken()));
        } catch (RuntimeException e) {
            // Network error, 5xx, client timeout: we genuinely do not know. Never log the message: it may echo the token.
            log.warn("Provider call failed for payment {} ({})", init.paymentId(), e.getClass().getSimpleName());
            return ChargeResult.timeout();
        }
    }
}