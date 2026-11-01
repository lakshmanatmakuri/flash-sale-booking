package com.example.booking.refund;

import com.example.booking.common.exception.ApiException;
import com.example.booking.common.exception.ResourceNotFoundException;
import com.example.booking.hold.dto.CreateHoldRequest;
import com.example.booking.hold.service.HoldService;
import com.example.booking.ledger.service.LedgerQueryService;
import com.example.booking.payment.dto.PayRequest;
import com.example.booking.payment.dto.PaymentMethod;
import com.example.booking.payment.dto.PaymentResult;
import com.example.booking.payment.provider.MockPaymentProvider;
import com.example.booking.payment.provider.MockPaymentProvider.RefundBehavior;
import com.example.booking.payment.service.PaymentService;
import com.example.booking.support.AbstractIntegrationTest;
import com.example.booking.support.TestFixtures;
import com.example.booking.support.TestFixtures.EventFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundFlowTest extends AbstractIntegrationTest {

    @Autowired HoldService holdService;
    @Autowired PaymentService paymentService;
    @Autowired CancellationService cancellation;
    @Autowired RefundExecutor refundExecutor;
    @Autowired MockPaymentProvider mock;
    @Autowired LedgerQueryService ledgerQuery;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;

    /** Refunds left over from other tests must not be retried (and must not change attempt counts) here. */
    @BeforeEach
    void parkLeftovers() { jdbc.update("UPDATE refunds SET attempts = 99 WHERE status <> 'SUCCEEDED'"); }

    @AfterEach
    void resetMock() { mock.setRefundBehavior(RefundBehavior.SUCCEED); }

    private PaymentResult pay(UUID user, EventFixture fx, int i, PaymentMethod method, String token) {
        UUID holdId = holdService.createHolds(user,
                new CreateHoldRequest(fx.eventId(), List.of(fx.eventSeatIds().get(i)))).get(0).id();
        return paymentService.pay(user, UUID.randomUUID().toString(), new PayRequest(List.of(holdId), method, token));
    }

    private PaymentResult payCard(UUID user, EventFixture fx, int i) { return pay(user, fx, i, PaymentMethod.CARD, "tok_success"); }
    private String str(String sql, Object... a) { return jdbc.queryForObject(sql, String.class, a); }
    private int count(String sql, Object... a) { return jdbc.queryForObject(sql, Integer.class, a); }
    private UUID organizerOf(EventFixture fx) { return jdbc.queryForObject("SELECT organizer_id FROM events WHERE id = ?", UUID.class, fx.eventId()); }

    // -----------------------------------------------------------------------------------------------------

    @Test
    void cancellingACardBookingReleasesTheSeatRefundsTheProviderAndReversesTheLedger() {
        EventFixture fx = fixtures.createPublishedEvent(1, 3, 4);
        List<UUID> users = fixtures.createUsers(2);
        UUID seat = fx.eventSeatIds().get(0), organizer = organizerOf(fx);
        PaymentResult paid = payCard(users.get(0), fx, 0);
        UUID bookingId = paid.payment().bookingId(), paymentId = paid.payment().paymentId();

        CancellationResponse r = cancellation.cancel(users.get(0), bookingId);

        assertThat(r.bookingStatus()).isEqualTo("CANCELLED");
        assertThat(r.refundStatus()).isEqualTo("SUCCEEDED");
        assertThat(str("SELECT status FROM bookings WHERE id = ?", bookingId)).isEqualTo("CANCELLED");
        assertThat(str("SELECT status FROM booking_items WHERE booking_id = ?", bookingId)).isEqualTo("RELEASED");
        assertThat(str("SELECT status FROM event_seats WHERE id = ?", seat)).isEqualTo("AVAILABLE");
        assertThat(str("SELECT status FROM payments WHERE id = ?", paymentId)).isEqualTo("REFUNDED");
        assertThat(mock.refundsApplied(r.refundId().toString())).isEqualTo(1);

        // Ledger: history untouched, corrections are NEW transactions
        assertThat(fixtures.ledgerEntries("PAYMENT_CAPTURE:" + paymentId)).hasSize(2);
        assertThat(fixtures.ledgerEntries("REFUND_SETTLEMENT:" + r.refundId())).containsExactlyInAnyOrder(
                "CREDIT ESCROW 500.00", "DEBIT ORGANIZER_PAYABLE:" + organizer + " 475.00", "DEBIT PLATFORM_FEE_REVENUE 25.00");
        assertThat(fixtures.ledgerEntries("REFUND_CAPTURE:" + r.refundId())).containsExactlyInAnyOrder(
                "DEBIT ESCROW 500.00", "CREDIT PROVIDER_CLEARING 500.00");
        assertThat(fixtures.balanceOf("ORGANIZER_PAYABLE:" + organizer)).isEqualByComparingTo("0");
        assertThat(ledgerQuery.trialBalance().balanced()).isTrue();

        // The released seat is genuinely back on sale
        assertThat(holdService.createHolds(users.get(1), new CreateHoldRequest(fx.eventId(), List.of(seat)))).hasSize(1);
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_events WHERE aggregate_id = ? ORDER BY seq",
                String.class, bookingId.toString())).containsExactly("BookingConfirmed", "BookingCancelled");
    }

    @Test
    void cancellingAWalletBookingCreditsTheWalletInOneTransactionWithoutTheProvider() {
        EventFixture fx = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);
        fixtures.topUpWallet(user, "1000.00");
        PaymentResult paid = pay(user, fx, 0, PaymentMethod.WALLET, null);
        assertThat(fixtures.balanceOf("USER_WALLET:" + user)).isEqualByComparingTo("500.00");

        CancellationResponse r = cancellation.cancel(user, paid.payment().bookingId());

        assertThat(r.refundStatus()).isEqualTo("SUCCEEDED");
        assertThat(fixtures.balanceOf("USER_WALLET:" + user)).isEqualByComparingTo("1000.00");
        assertThat(mock.refundCalls(r.refundId().toString())).as("no provider involved").isZero();
        assertThat(ledgerQuery.trialBalance().balanced()).isTrue();
    }

    @Test
    void aFailedRefundKeepsTheMoneyInEscrowAndIsRetriedUntilItSucceeds() {
        EventFixture fx = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);
        PaymentResult paid = payCard(user, fx, 0);
        mock.setRefundBehavior(RefundBehavior.FAIL);

        CancellationResponse r = cancellation.cancel(user, paid.payment().bookingId());

        // The booking and seat are already compensated; the MONEY is still owed
        assertThat(r.bookingStatus()).isEqualTo("CANCELLED");
        assertThat(r.refundStatus()).isEqualTo("FAILED");
        assertThat(str("SELECT status FROM payments WHERE id = ?", paid.payment().paymentId())).isEqualTo("REFUND_PENDING");
        assertThat(fixtures.ledgerEntries("REFUND_SETTLEMENT:" + r.refundId())).hasSize(3);
        assertThat(fixtures.ledgerEntries("REFUND_CAPTURE:" + r.refundId())).as("escrow still holds the customer's money").isEmpty();
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_events WHERE aggregate_id = ? ORDER BY seq",
                String.class, paid.payment().paymentId().toString())).contains("RefundFailed");

        mock.setRefundBehavior(RefundBehavior.SUCCEED);              // the provider recovers
        refundExecutor.recoverStuck();

        assertThat(str("SELECT status FROM refunds WHERE id = ?", r.refundId())).isEqualTo("SUCCEEDED");
        assertThat(count("SELECT attempts FROM refunds WHERE id = ?", r.refundId())).isEqualTo(2);
        assertThat(str("SELECT status FROM payments WHERE id = ?", paid.payment().paymentId())).isEqualTo("REFUNDED");
        assertThat(fixtures.ledgerEntries("REFUND_CAPTURE:" + r.refundId())).hasSize(2);
        assertThat(ledgerQuery.trialBalance().balanced()).isTrue();
    }

    @Test
    void aRefundThatTimedOutAfterMovingMoneyIsConfirmedWithoutRefundingTwice() {
        EventFixture fx = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);
        PaymentResult paid = payCard(user, fx, 0);
        mock.setRefundBehavior(RefundBehavior.TIMEOUT_REFUNDED);     // money moved, the response was lost

        CancellationResponse r = cancellation.cancel(user, paid.payment().bookingId());
        assertThat(r.refundStatus()).as("we do not know yet").isEqualTo("UNKNOWN");
        assertThat(fixtures.ledgerEntries("REFUND_CAPTURE:" + r.refundId())).isEmpty();

        refundExecutor.recoverStuck();

        assertThat(str("SELECT status FROM refunds WHERE id = ?", r.refundId())).isEqualTo("SUCCEEDED");
        assertThat(mock.refundCalls(r.refundId().toString())).isEqualTo(2);
        assertThat(mock.refundsApplied(r.refundId().toString())).as("the provider refunded exactly once").isEqualTo(1);
        assertThat(fixtures.ledgerEntries("REFUND_CAPTURE:" + r.refundId())).hasSize(2);
    }

    @Test
    void aRefundTheProviderNeverReceivedIsRetriedAndEventuallyDelivered() {
        EventFixture fx = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);
        PaymentResult paid = payCard(user, fx, 0);
        mock.setRefundBehavior(RefundBehavior.TIMEOUT_LOST);

        CancellationResponse r = cancellation.cancel(user, paid.payment().bookingId());
        refundExecutor.recoverStuck();
        assertThat(str("SELECT status FROM refunds WHERE id = ?", r.refundId())).isEqualTo("UNKNOWN");
        assertThat(count("SELECT attempts FROM refunds WHERE id = ?", r.refundId())).isEqualTo(2);

        mock.setRefundBehavior(RefundBehavior.SUCCEED);
        refundExecutor.recoverStuck();
        assertThat(str("SELECT status FROM refunds WHERE id = ?", r.refundId())).isEqualTo("SUCCEEDED");
        assertThat(mock.refundsApplied(r.refundId().toString())).isEqualTo(1);
    }

    @Test
    void tenConcurrentCancelsRefundExactlyOnce() throws Exception {
        EventFixture fx = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);
        PaymentResult paid = payCard(user, fx, 0);
        UUID bookingId = paid.payment().bookingId();

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CancellationResponse>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> { start.await(); return cancellation.cancel(user, bookingId); }));
        }
        start.countDown();
        List<CancellationResponse> results = new ArrayList<>();
        for (Future<CancellationResponse> f : futures) results.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(results.stream().filter(x -> !x.replayed()).count()).as("one real cancellation").isEqualTo(1);
        assertThat(results.stream().map(CancellationResponse::refundId).distinct()).hasSize(1);
        UUID refundId = results.get(0).refundId();
        assertThat(count("SELECT count(*) FROM refunds WHERE booking_id = ?", bookingId)).isEqualTo(1);
        assertThat(mock.refundsApplied(refundId.toString())).isEqualTo(1);
        assertThat(fixtures.ledgerEntries("REFUND_SETTLEMENT:" + refundId)).hasSize(3);
        assertThat(ledgerQuery.trialBalance().balanced()).isTrue();
    }

    @Test
    void cancellationRulesAreEnforced() {
        EventFixture fx = fixtures.createPublishedEvent(1, 4, 4);
        List<UUID> users = fixtures.createUsers(2);
        UUID owner = users.get(0), stranger = users.get(1);

        PaymentResult paid = payCard(owner, fx, 0);
        assertThatThrownBy(() -> cancellation.cancel(stranger, paid.payment().bookingId()))
                .isInstanceOf(ResourceNotFoundException.class);                       // not yours = does not exist

        PaymentResult declined = pay(owner, fx, 1, PaymentMethod.CARD, "tok_declined");
        assertThatThrownBy(() -> cancellation.cancel(owner, declined.payment().bookingId()))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "BOOKING_NOT_CANCELLABLE");

        jdbc.update("UPDATE events SET starts_at = now() - interval '1 hour' WHERE id = ?", fx.eventId());
        assertThatThrownBy(() -> cancellation.cancel(owner, paid.payment().bookingId()))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "EVENT_ALREADY_STARTED");
        assertThat(str("SELECT status FROM bookings WHERE id = ?", paid.payment().bookingId())).isEqualTo("CONFIRMED");
    }
}