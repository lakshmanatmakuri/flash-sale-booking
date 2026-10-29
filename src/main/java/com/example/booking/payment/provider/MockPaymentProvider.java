package com.example.booking.payment.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** DEV/TEST ONLY. Its "provider side" memory lives in this JVM, so it forgets everything on restart. */
@Component
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "mock", matchIfMissing = true)
public class MockPaymentProvider implements PaymentProvider {

    // What the "provider" actually knows, independent of what it told the caller.
    private final ConcurrentHashMap<String, ProviderStatus> providerSide = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> calls = new ConcurrentHashMap<>();

    @Override
    public String name() { return "mock"; }

    @Override
    public ChargeResult charge(ChargeRequest r) {
        calls.computeIfAbsent(r.reference(), k -> new AtomicInteger()).incrementAndGet();
        String ref = "mock_" + r.reference();

        // Idempotent, exactly like a real provider: a known reference never charges again.
        ProviderStatus known = providerSide.get(r.reference());
        if (known == ProviderStatus.SUCCEEDED) return new ChargeResult(ChargeOutcome.SUCCEEDED, ref, null);
        if (known == ProviderStatus.FAILED) return new ChargeResult(ChargeOutcome.FAILED, ref, "card_declined");

        String token = r.paymentMethodToken() == null ? "" : r.paymentMethodToken();
        return switch (token) {
            case "tok_success" -> {
                providerSide.put(r.reference(), ProviderStatus.SUCCEEDED);
                yield new ChargeResult(ChargeOutcome.SUCCEEDED, ref, null);
            }
            case "tok_declined" -> {
                providerSide.put(r.reference(), ProviderStatus.FAILED);
                yield new ChargeResult(ChargeOutcome.FAILED, ref, "card_declined");
            }
            case "tok_timeout_charged" -> {                 // money moved, but the response never arrived
                providerSide.put(r.reference(), ProviderStatus.SUCCEEDED);
                yield ChargeResult.timeout();
            }
            case "tok_timeout_lost" ->                      // request never arrived: provider knows nothing
                    ChargeResult.timeout();
            case "tok_processing" -> {                      // accepted, finishes later
                providerSide.put(r.reference(), ProviderStatus.PENDING);
                yield new ChargeResult(ChargeOutcome.UNKNOWN, ref, null);
            }
            default -> {
                providerSide.put(r.reference(), ProviderStatus.FAILED);
                yield new ChargeResult(ChargeOutcome.FAILED, null, "invalid_payment_method");
            }
        };
    }

    @Override
    public ProviderStatus queryStatus(String reference) {
        ProviderStatus s = providerSide.getOrDefault(reference, ProviderStatus.NOT_FOUND);
        if (s == ProviderStatus.PENDING) {
            providerSide.put(reference, ProviderStatus.SUCCEEDED);   // "processing" completes after the first look
        }
        return s;
    }

    /** Test hook: how many times was charge() called for this reference? */
    public int chargeCalls(String reference) {
        AtomicInteger c = calls.get(reference);
        return c == null ? 0 : c.get();
    }
}
