package com.example.booking.webhook;

import com.example.booking.payment.repository.PaymentDao;
import com.example.booking.payment.service.PaymentSettler;
import com.example.booking.refund.LateCaptureService;
import com.example.booking.refund.RefundSettler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookProcessor {

    /** result: processed | duplicate | ignored. refundToExecute: run it AFTER this transaction has committed. */
    public record Outcome(String result, Optional<UUID> refundToExecute) {
        static Outcome processed() { return new Outcome("processed", Optional.empty()); }
        static Outcome ignored() { return new Outcome("ignored", Optional.empty()); }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final PaymentDao payments;
    private final PaymentSettler paymentSettler;
    private final RefundSettler refundSettler;
    private final LateCaptureService lateCapture;

    @Transactional
    public Outcome process(String provider, WebhookEvent event, UUID reference, String rawBody) {
        // The unique key is the guard. A concurrent duplicate WAITS for this transaction, then inserts nothing.
        int inserted = jdbc.update("""
                INSERT INTO webhook_events (provider, provider_event_id, event_type, payload)
                VALUES (:p, :id, :type, :payload)
                ON CONFLICT (provider, provider_event_id) DO NOTHING
                """, new MapSqlParameterSource().addValue("p", provider).addValue("id", event.id())
                .addValue("type", event.type()).addValue("payload", rawBody));
        if (inserted == 0) return new Outcome("duplicate", Optional.empty());

        WebhookEvent.Data data = event.data();
        return switch (event.type()) {
            case "payment.succeeded" -> paymentSucceeded(reference, data.providerRef());
            case "payment.failed" -> {
                paymentSettler.settleFailure(reference, data.providerRef(), orDefault(data.failureCode(), "provider_failed"));
                yield Outcome.processed();                       // already settled elsewhere = harmless no-op
            }
            case "refund.succeeded" -> {
                refundSettler.settleSuccess(reference, data.providerRef());
                yield Outcome.processed();
            }
            case "refund.failed" -> {
                refundSettler.settleFailure(reference, orDefault(data.failureCode(), "provider_failed"));
                yield Outcome.processed();
            }
            default -> {
                log.info("Ignoring webhook type {}", event.type());
                yield Outcome.ignored();
            }
        };
    }

    private Outcome paymentSucceeded(UUID paymentId, String providerRef) {
        String status = payments.findStatus(paymentId).orElse(null);
        if (status == null) {
            log.warn("Webhook for unknown payment {}", paymentId);
            return Outcome.ignored();
        }
        if (status.equals("PENDING") || status.equals("UNKNOWN")) {
            if (paymentSettler.settleSuccess(paymentId, providerRef)) return Outcome.processed();
            // Lost a race with the request thread or the recovery job. Look again with fresh state:
            // if they FAILED it, the money still arrived, and that is the late-success case below.
            status = payments.findStatus(paymentId).orElse("");
        }
        if (status.equals("FAILED")) {
            return new Outcome("processed", lateCapture.compensate(paymentId, providerRef));
        }
        return Outcome.processed();      // SUCCEEDED / REFUND_PENDING / REFUNDED: already accounted for
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
