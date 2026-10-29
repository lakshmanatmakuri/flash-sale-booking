package com.example.booking.payment;

import com.example.booking.common.exception.ApiException;
import com.example.booking.hold.dto.CreateHoldRequest;
import com.example.booking.hold.service.HoldService;
import com.example.booking.payment.dto.PayRequest;
import com.example.booking.payment.dto.PaymentMethod;
import com.example.booking.payment.dto.PaymentResult;
import com.example.booking.payment.entity.PaymentStatus;
import com.example.booking.payment.provider.MockPaymentProvider;
import com.example.booking.payment.service.PaymentService;
import com.example.booking.support.AbstractIntegrationTest;
import com.example.booking.support.TestFixtures;
import com.example.booking.support.TestFixtures.EventFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentFlowTest extends AbstractIntegrationTest {

    @Autowired HoldService holdService;
    @Autowired PaymentService paymentService;
    @Autowired MockPaymentProvider mockProvider;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;

    private UUID hold(UUID user, EventFixture fx, int seatIndex) {
        return holdService.createHolds(user,
                new CreateHoldRequest(fx.eventId(), List.of(fx.eventSeatIds().get(seatIndex)))).get(0).id();
    }

    private PaymentResult pay(UUID user, UUID holdId, String token) {
        return paymentService.pay(user, UUID.randomUUID().toString(),
                new PayRequest(List.of(holdId), PaymentMethod.CARD, token));
    }

    private String str(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    @Test
    void successfulCardPaymentConfirmsTheBookingAndBooksTheSeat() {
        var fx = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);
        UUID seat = fx.eventSeatIds().get(0);
        UUID holdId = hold(user, fx, 0);

        PaymentResult r = pay(user, holdId, "tok_success");

        assertThat(r.replayed()).isFalse();
        assertThat(r.payment().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        UUID bookingId = r.payment().bookingId();
        assertThat(str("SELECT status FROM bookings WHERE id = ?", bookingId)).isEqualTo("CONFIRMED");
        assertThat(str("SELECT status FROM booking_items WHERE booking_id = ?", bookingId)).isEqualTo("CONFIRMED");
        assertThat(str("SELECT status FROM event_seats WHERE id = ?", seat)).isEqualTo("BOOKED");
        assertThat(str("SELECT status FROM seat_holds WHERE id = ?", holdId)).isEqualTo("CONVERTED");
    }

    @Test
    void declinedCardReleasesTheSeatForSomeoneElse() {
        var fx = fixtures.createPublishedEvent(1, 3, 4);
        List<UUID> users = fixtures.createUsers(2);
        UUID seat = fx.eventSeatIds().get(0);

        PaymentResult r = pay(users.get(0), hold(users.get(0), fx, 0), "tok_declined");

        assertThat(r.payment().status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(r.payment().failureCode()).isEqualTo("card_declined");
        assertThat(str("SELECT status FROM bookings WHERE id = ?", r.payment().bookingId())).isEqualTo("FAILED");
        assertThat(str("SELECT status FROM booking_items WHERE booking_id = ?", r.payment().bookingId()))
                .isEqualTo("RELEASED");
        assertThat(str("SELECT status FROM event_seats WHERE id = ?", seat)).isEqualTo("AVAILABLE");

        // The seat is genuinely back in the pool
        assertThat(holdService.createHolds(users.get(1),
                new CreateHoldRequest(fx.eventId(), List.of(seat)))).hasSize(1);
    }

    @Test
    void chargedButTimedOut_recoveryFindsTheMoneyAndConfirmsTheBooking() {
        var fx = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);
        UUID seat = fx.eventSeatIds().get(0);

        PaymentResult r = pay(user, hold(user, fx, 0), "tok_timeout_charged");
        UUID paymentId = r.payment().paymentId();

        // We do not know yet: the seat stays locked, nobody else can take it, the booking waits.
        assertThat(r.payment().status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(str("SELECT status FROM event_seats WHERE id = ?", seat)).isEqualTo("PAYMENT_PENDING");

        paymentService.recoverStuckPayments();

        assertThat(str("SELECT status FROM payments WHERE id = ?", paymentId)).isEqualTo("SUCCEEDED");
        assertThat(str("SELECT status FROM bookings WHERE id = ?", r.payment().bookingId())).isEqualTo("CONFIRMED");
        assertThat(str("SELECT status FROM event_seats WHERE id = ?", seat)).isEqualTo("BOOKED");

        paymentService.recoverStuckPayments();       // running recovery again changes nothing
        assertThat(str("SELECT status FROM payments WHERE id = ?", paymentId)).isEqualTo("SUCCEEDED");
        assertThat(mockProvider.chargeCalls(paymentId.toString()))
                .as("recovery asks the provider, it never charges again").isEqualTo(1);
    }

    @Test
    void timedOutAndNeverCharged_recoveryFailsThePaymentAndFreesTheSeat() {
        var fx = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);
        UUID seat = fx.eventSeatIds().get(0);

        PaymentResult r = pay(user, hold(user, fx, 0), "tok_timeout_lost");
        assertThat(r.payment().status()).isEqualTo(PaymentStatus.UNKNOWN);

        paymentService.recoverStuckPayments();

        UUID paymentId = r.payment().paymentId();
        assertThat(str("SELECT status FROM payments WHERE id = ?", paymentId)).isEqualTo("FAILED");
        assertThat(str("SELECT failure_code FROM payments WHERE id = ?", paymentId)).isEqualTo("never_reached_provider");
        assertThat(str("SELECT status FROM bookings WHERE id = ?", r.payment().bookingId())).isEqualTo("FAILED");
        assertThat(str("SELECT status FROM event_seats WHERE id = ?", seat)).isEqualTo("AVAILABLE");
    }

    @Test
    void aPaymentThatIsStillProcessingResolvesOnALaterCheck() {
        var fx = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);

        PaymentResult r = pay(user, hold(user, fx, 0), "tok_processing");
        UUID paymentId = r.payment().paymentId();
        assertThat(r.payment().status()).isEqualTo(PaymentStatus.UNKNOWN);

        paymentService.recoverStuckPayments();       // provider says PENDING: leave it alone
        assertThat(str("SELECT status FROM payments WHERE id = ?", paymentId)).isEqualTo("UNKNOWN");

        paymentService.recoverStuckPayments();       // provider has finished by now
        assertThat(str("SELECT status FROM payments WHERE id = ?", paymentId)).isEqualTo("SUCCEEDED");
        assertThat(str("SELECT status FROM bookings WHERE id = ?", r.payment().bookingId())).isEqualTo("CONFIRMED");
    }

    @Test
    void tenIdenticalRequestsCreateOnePaymentAndChargeOnce() throws Exception {
        var fx = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);
        UUID holdId = hold(user, fx, 0);
        String key = UUID.randomUUID().toString();
        PayRequest req = new PayRequest(List.of(holdId), PaymentMethod.CARD, "tok_success");

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PaymentResult>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return paymentService.pay(user, key, req);
            }));
        }
        start.countDown();
        List<PaymentResult> results = new ArrayList<>();
        for (Future<PaymentResult> f : futures) results.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();

        Set<UUID> paymentIds = results.stream().map(x -> x.payment().paymentId()).collect(Collectors.toSet());
        assertThat(paymentIds).as("all ten calls refer to the same payment").hasSize(1);
        assertThat(results.stream().filter(x -> !x.replayed()).count()).as("exactly one real execution").isEqualTo(1);

        UUID paymentId = paymentIds.iterator().next();
        assertThat(mockProvider.chargeCalls(paymentId.toString())).as("the provider was charged once").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE user_id = ?", Integer.class, user))
                .isEqualTo(1);
        assertThat(str("SELECT status FROM payments WHERE id = ?", paymentId)).isEqualTo("SUCCEEDED");
    }

    @Test
    void anExpiredOrForeignHoldCannotBePaid() {
        var fx = fixtures.createPublishedEvent(1, 3, 4);
        List<UUID> users = fixtures.createUsers(2);
        UUID owner = users.get(0), stranger = users.get(1);

        UUID expiring = hold(owner, fx, 0);
        jdbc.update("UPDATE seat_holds SET expires_at = now() - interval '1 second' WHERE id = ?", expiring);
        assertThatThrownBy(() -> pay(owner, expiring, "tok_success"))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "HOLD_EXPIRED");

        UUID mine = hold(owner, fx, 1);
        assertThatThrownBy(() -> pay(stranger, mine, "tok_success"))        // someone else's hold looks the same
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "HOLD_EXPIRED");
        assertThat(str("SELECT status FROM seat_holds WHERE id = ?", mine)).as("rolled back").isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE user_id IN (?, ?)", Integer.class,
                owner, stranger)).isZero();
    }

    @Test
    void aConvertedHoldIsNotSweptWhileMoneyIsInFlight() {
        var fx = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);
        UUID seat = fx.eventSeatIds().get(0);
        UUID holdId = hold(user, fx, 0);

        pay(user, holdId, "tok_timeout_charged");                            // outcome unknown, seat PAYMENT_PENDING
        jdbc.update("UPDATE seat_holds SET expires_at = now() - interval '1 minute' WHERE id = ?", holdId);

        holdService.expireDueBatch();                                        // the sweeper runs

        assertThat(str("SELECT status FROM seat_holds WHERE id = ?", holdId)).isEqualTo("CONVERTED");
        assertThat(str("SELECT status FROM event_seats WHERE id = ?", seat)).isEqualTo("PAYMENT_PENDING");
    }
}