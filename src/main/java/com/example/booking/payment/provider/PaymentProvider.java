
package com.example.booking.payment.provider;

import java.math.BigDecimal;

/**
 * The ONLY thing booking logic knows about payments. To use Razorpay or Stripe, write another implementation,
 * give it @ConditionalOnProperty(app.payment.provider=razorpay), and nothing else changes.
 *
 * Contract every implementation must honor:
 *  - charge() is IDEMPOTENT on `reference`: charging the same reference twice never moves money twice
 *    (Stripe: Idempotency-Key header; Razorpay: order/receipt id).
 *  - charge() must enforce a client-side timeout shorter than app.payment.recovery-min-age-seconds,
 *    and map timeouts to TIMEOUT (or throw: PaymentService treats exceptions as TIMEOUT).
 *  - queryStatus() answers "what do you know about this reference?" and is used by recovery.
 */
public interface PaymentProvider {

    String name();

    ChargeResult charge(ChargeRequest request);

    ProviderStatus queryStatus(String reference);

    enum ChargeOutcome {
        SUCCEEDED, FAILED, TIMEOUT, UNKNOWN
    }

    enum ProviderStatus {
        SUCCEEDED, FAILED, PENDING, NOT_FOUND
    }

    record ChargeRequest(
            String reference,
            BigDecimal amount,
            String currency,
            String paymentMethodToken
    ) {}

    record ChargeResult(
            ChargeOutcome outcome,
            String providerRef,
            String failureCode
    ) {
        public static ChargeResult timeout() {
            return new ChargeResult(ChargeOutcome.TIMEOUT, null, null);
        }
    }

    /**
     * Refunds a previous charge. IDEMPOTENT on request.reference():
     * calling it again with the same reference never refunds twice
     * and returns the original outcome. This makes retries and crash recovery safe.
     */
    RefundResult refund(RefundRequest request);

    enum RefundOutcome {
        SUCCEEDED, FAILED, TIMEOUT, UNKNOWN
    }

    /**
     * reference = our refund id;
     * paymentReference = the reference used when charging (our payment id).
     */
    record RefundRequest(
            String reference,
            String paymentReference,
            BigDecimal amount,
            String currency
    ) {}

    record RefundResult(
            RefundOutcome outcome,
            String providerRef,
            String failureCode
    ) {
        public static RefundResult timeout() {
            return new RefundResult(RefundOutcome.TIMEOUT, null, null);
        }
    }
}
