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

    enum ChargeOutcome { SUCCEEDED, FAILED, TIMEOUT, UNKNOWN }

    enum ProviderStatus { SUCCEEDED, FAILED, PENDING, NOT_FOUND }

    record ChargeRequest(String reference, BigDecimal amount, String currency, String paymentMethodToken) {}

    record ChargeResult(ChargeOutcome outcome, String providerRef, String failureCode) {
        public static ChargeResult timeout() {
            return new ChargeResult(ChargeOutcome.TIMEOUT, null, null);
        }
    }
}