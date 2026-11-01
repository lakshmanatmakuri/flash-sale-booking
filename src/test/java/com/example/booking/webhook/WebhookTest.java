package com.example.booking.webhook;

import com.example.booking.hold.dto.CreateHoldRequest;
import com.example.booking.hold.service.HoldService;
import com.example.booking.payment.dto.PayRequest;
import com.example.booking.payment.dto.PaymentMethod;
import com.example.booking.payment.dto.PaymentResult;
import com.example.booking.payment.provider.MockPaymentProvider;
import com.example.booking.payment.provider.MockPaymentProvider.RefundBehavior;
import com.example.booking.payment.service.PaymentService;
import com.example.booking.refund.CancellationService;
import com.example.booking.refund.RefundExecutor;
import com.example.booking.support.AbstractIntegrationTest;
import com.example.booking.support.TestFixtures;
import com.example.booking.support.TestFixtures.EventFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebhookTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired WebhookProperties props;
    @Autowired HoldService holdService;
    @Autowired PaymentService paymentService;
    @Autowired CancellationService cancellation;
    @Autowired RefundExecutor refundExecutor;
    @Autowired MockPaymentProvider mock;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void parkLeftovers() { jdbc.update("UPDATE refunds SET attempts = 99 WHERE status <> 'SUCCEEDED'"); }

    @AfterEach
    void resetMock() { mock.setRefundBehavior(RefundBehavior.SUCCEED); }

    private String evt() { return "evt_" + UUID.randomUUID(); }
    private String str(String sql, Object... a) { return jdbc.queryForObject(sql, String.class, a); }
    private int count(String sql, Object... a) { return jdbc.queryForObject(sql, Integer.class, a); }

    private String body(String id, String type, UUID reference) throws Exception {
        return om.writeValueAsString(Map.of("id", id, "type", type,
                "data", Map.of("reference", reference.toString(), "providerRef", "mock_" + reference)));
    }

    private ResultActions send(String body) throws Exception {
        return send(body, WebhookSignature.header(props.secret(), Instant.now().getEpochSecond(), body));
    }

    private ResultActions send(String body, String signature) throws Exception {
        var req = post("/api/webhooks/payments").contentType(MediaType.APPLICATION_JSON).content(body);
        if (signature != null) req.header("X-Webhook-Signature", signature);
        return mvc.perform(req);
    }

    /** A payment whose outcome we do not know: charged at the provider, response lost. */
    private PaymentResult unknownPayment(UUID user, EventFixture fx, int i, String token) {
        UUID holdId = holdService.createHolds(user,
                new CreateHoldRequest(fx.eventId(), List.of(fx.eventSeatIds().get(i)))).get(0).id();
        return paymentService.pay(user, UUID.randomUUID().toString(), new PayRequest(List.of(holdId), PaymentMethod.CARD, token));
    }

    /** Gives up on an unknown payment (seat freed, payment FAILED), then another user takes the seat. Returns payment id. */
    private UUID gaveUpAndResold(EventFixture fx, UUID firstBuyer, UUID secondBuyer) {
        PaymentResult p = unknownPayment(firstBuyer, fx, 0, "tok_timeout_lost");
        paymentService.recoverStuckPayments();
        assertThat(str("SELECT status FROM payments WHERE id = ?", p.payment().paymentId())).isEqualTo("FAILED");
        holdService.createHolds(secondBuyer, new CreateHoldRequest(fx.eventId(), List.of(fx.eventSeatIds().get(0))));
        return p.payment().paymentId();
    }

    // -----------------------------------------------------------------------------------------------------

    @Test
    void forgedStaleOrUnsignedWebhooksAreRejectedAndChangeNothing() throws Exception {
        EventFixture fx = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);
        PaymentResult p = unknownPayment(user, fx, 0, "tok_timeout_charged");
        String id = evt();
        String b = body(id, "payment.succeeded", p.payment().paymentId());

        send(b, null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
        send(b, WebhookSignature.header("some-other-secret-value", Instant.now().getEpochSecond(), b))
                .andExpect(status().isUnauthorized());
        send(b, WebhookSignature.header(props.secret(), Instant.now().getEpochSecond() - 3600, b))
                .andExpect(status().isUnauthorized());                                  // correct secret, but a replay
        send(b + " ", WebhookSignature.header(props.secret(), Instant.now().getEpochSecond(), b))
                .andExpect(status().isUnauthorized());                                  // body tampered after signing

        assertThat(str("SELECT status FROM payments WHERE id = ?", p.payment().paymentId())).isEqualTo("UNKNOWN");
        assertThat(count("SELECT count(*) FROM webhook_events WHERE provider_event_id = ?", id)).isZero();
    }

    @Test
    void aSuccessWebhookSettlesAPendingPaymentAndDuplicatesChangeNothing() throws Exception {
        EventFixture fx = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);
        PaymentResult p = unknownPayment(user, fx, 0, "tok_timeout_charged");
        UUID paymentId = p.payment().paymentId(), bookingId = p.payment().bookingId();
        String id = evt();
        String b = body(id, "payment.succeeded", paymentId);

        send(b).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("processed"));
        assertThat(str("SELECT status FROM payments WHERE id = ?", paymentId)).isEqualTo("SUCCEEDED");
        assertThat(str("SELECT status FROM bookings WHERE id = ?", bookingId)).isEqualTo("CONFIRMED");
        assertThat(str("SELECT status FROM event_seats WHERE id = ?", fx.eventSeatIds().get(0))).isEqualTo("BOOKED");

        send(b).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("duplicate"));     // same event id
        send(body(evt(), "payment.succeeded", paymentId)).andExpect(status().isOk());              // provider re-sent under a NEW id

        assertThat(fixtures.ledgerEntries("PAYMENT_CAPTURE:" + paymentId)).hasSize(2);
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_events WHERE aggregate_id = ?",
                String.class, bookingId.toString())).containsExactly("BookingConfirmed");
    }

    @Test
    void tenConcurrentIdenticalWebhooksAreProcessedOnce() throws Exception {
        EventFixture fx = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);
        PaymentResult p = unknownPayment(user, fx, 0, "tok_timeout_charged");
        String b = body(evt(), "payment.succeeded", p.payment().paymentId());
        String sig = WebhookSignature.header(props.secret(), Instant.now().getEpochSecond(), b);

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                String json = send(b, sig).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                return om.readTree(json).get("status").asText();
            }));
        }
        start.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> f : futures) results.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(results.stream().filter("processed"::equals).count()).isEqualTo(1);
        assertThat(results.stream().filter("duplicate"::equals).count()).isEqualTo(9);
        assertThat(fixtures.ledgerEntries("PAYMENT_CAPTURE:" + p.payment().paymentId())).hasSize(2);
    }

    @Test
    void aFailureWebhookFreesTheSeat() throws Exception {
        EventFixture fx = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);
        PaymentResult p = unknownPayment(user, fx, 0, "tok_timeout_lost");

        send(body(evt(), "payment.failed", p.payment().paymentId())).andExpect(status().isOk());

        assertThat(str("SELECT status FROM payments WHERE id = ?", p.payment().paymentId())).isEqualTo("FAILED");
        assertThat(str("SELECT status FROM event_seats WHERE id = ?", fx.eventSeatIds().get(0))).isEqualTo("AVAILABLE");
    }

    @Test
    void lateSuccessAfterWeGaveUpTriggersAnAutomaticRefund() throws Exception {
        EventFixture fx = fixtures.createPublishedEvent(1, 3, 4);
        List<UUID> users = fixtures.createUsers(2);
        UUID seat = fx.eventSeatIds().get(0);
        PaymentResult p = unknownPayment(users.get(0), fx, 0, "tok_timeout_lost");
        UUID paymentId = p.payment().paymentId(), bookingId = p.payment().bookingId();

        paymentService.recoverStuckPayments();       // the provider knows nothing yet, so we give up and free the seat
        assertThat(str("SELECT status FROM payments WHERE id = ?", paymentId)).isEqualTo("FAILED");
        holdService.createHolds(users.get(1), new CreateHoldRequest(fx.eventId(), List.of(seat)));   // someone else buys it

        // ...and THEN the charge lands at the provider
        send(body(evt(), "payment.succeeded", paymentId)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("processed"));

        UUID refundId = jdbc.queryForObject("SELECT id FROM refunds WHERE payment_id = ?", UUID.class, paymentId);
        assertThat(str("SELECT reason FROM refunds WHERE id = ?", refundId)).isEqualTo("LATE_PAYMENT_SUCCESS");
        assertThat(str("SELECT status FROM refunds WHERE id = ?", refundId)).isEqualTo("SUCCEEDED");
        assertThat(str("SELECT status FROM payments WHERE id = ?", paymentId)).isEqualTo("REFUNDED");
        assertThat(mock.refundsApplied(refundId.toString())).isEqualTo(1);
        assertThat(str("SELECT status FROM bookings WHERE id = ?", bookingId)).as("never re-booked").isEqualTo("FAILED");
        assertThat(str("SELECT status FROM event_seats WHERE id = ?", seat)).as("the other buyer keeps the seat").isEqualTo("HELD");

        // The books tell the whole story: money in, money out, nothing left in escrow for this payment
        assertThat(fixtures.ledgerEntries("PAYMENT_CAPTURE:" + paymentId))
                .containsExactlyInAnyOrder("DEBIT PROVIDER_CLEARING 500.00", "CREDIT ESCROW 500.00");
        assertThat(fixtures.ledgerEntries("REFUND_CAPTURE:" + refundId))
                .containsExactlyInAnyOrder("CREDIT PROVIDER_CLEARING 500.00", "DEBIT ESCROW 500.00");

        // The provider re-sends the same fact under a new event id: still exactly one refund, one provider refund
        send(body(evt(), "payment.succeeded", paymentId)).andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM refunds WHERE payment_id = ?", paymentId)).isEqualTo(1);
        assertThat(mock.refundsApplied(refundId.toString())).isEqualTo(1);
        assertThat(fixtures.ledgerEntries("PAYMENT_CAPTURE:" + paymentId)).hasSize(2);
    }

    @Test
    void lateSuccessWhoseRefundTimesOutIsFinishedByARetry() throws Exception {
        EventFixture fx = fixtures.createPublishedEvent(1, 3, 4);
        List<UUID> users = fixtures.createUsers(2);
        UUID paymentId = gaveUpAndResold(fx, users.get(0), users.get(1));
        mock.setRefundBehavior(RefundBehavior.TIMEOUT_LOST);               // the refund call fails and nothing happens

        // The webhook itself still succeeds: the refund is durable and does not depend on this request
        send(body(evt(), "payment.succeeded", paymentId)).andExpect(status().isOk());

        UUID refundId = jdbc.queryForObject("SELECT id FROM refunds WHERE payment_id = ?", UUID.class, paymentId);
        assertThat(str("SELECT status FROM refunds WHERE id = ?", refundId)).isEqualTo("UNKNOWN");
        assertThat(str("SELECT status FROM payments WHERE id = ?", paymentId)).as("money still owed").isEqualTo("REFUND_PENDING");
        assertThat(fixtures.ledgerEntries("REFUND_CAPTURE:" + refundId)).isEmpty();

        mock.setRefundBehavior(RefundBehavior.SUCCEED);                    // provider recovers
        jdbc.update("UPDATE refunds SET attempts = 1 WHERE id = ?", refundId);
        refundExecutor.execute(refundId);                                  // what the recovery job does

        assertThat(str("SELECT status FROM refunds WHERE id = ?", refundId)).isEqualTo("SUCCEEDED");
        assertThat(str("SELECT status FROM payments WHERE id = ?", paymentId)).isEqualTo("REFUNDED");
        assertThat(mock.refundsApplied(refundId.toString())).isEqualTo(1);
        assertThat(fixtures.ledgerEntries("REFUND_CAPTURE:" + refundId)).hasSize(2);
    }

    @Test
    void aRefundSucceededWebhookCompletesAnUnknownRefundAndALaterRetryChangesNothing() throws Exception {
        EventFixture fx = fixtures.createPublishedEvent(1, 3, 4);
        List<UUID> users = fixtures.createUsers(2);
        UUID paymentId = gaveUpAndResold(fx, users.get(0), users.get(1));
        mock.setRefundBehavior(RefundBehavior.TIMEOUT_LOST);
        send(body(evt(), "payment.succeeded", paymentId)).andExpect(status().isOk());
        UUID refundId = jdbc.queryForObject("SELECT id FROM refunds WHERE payment_id = ?", UUID.class, paymentId);
        assertThat(str("SELECT status FROM refunds WHERE id = ?", refundId)).isEqualTo("UNKNOWN");

        // The provider tells us asynchronously that it did refund. The reference is OUR refund id.
        String id = evt();
        send(body(id, "refund.succeeded", refundId)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("processed"));
        send(body(id, "refund.succeeded", refundId)).andExpect(jsonPath("$.status").value("duplicate"));
        send(body(evt(), "refund.succeeded", refundId)).andExpect(status().isOk());      // new id, same fact: guarded flip, no-op

        assertThat(str("SELECT status FROM refunds WHERE id = ?", refundId)).isEqualTo("SUCCEEDED");
        assertThat(str("SELECT status FROM payments WHERE id = ?", paymentId)).isEqualTo("REFUNDED");
        assertThat(fixtures.ledgerEntries("REFUND_CAPTURE:" + refundId)).hasSize(2);     // posted exactly once

        refundExecutor.execute(refundId);                                  // a stray retry after the fact
        assertThat(mock.refundsApplied(refundId.toString())).isZero();     // the lost mock never applied it; we did not re-ask
        assertThat(fixtures.ledgerEntries("REFUND_CAPTURE:" + refundId)).hasSize(2);
    }
}