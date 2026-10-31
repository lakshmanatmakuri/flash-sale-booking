package com.example.booking.ledger;

import com.example.booking.common.exception.ApiException;
import com.example.booking.hold.dto.CreateHoldRequest;
import com.example.booking.hold.service.HoldService;
import com.example.booking.ledger.service.LedgerPostings;
import com.example.booking.payment.dto.PayRequest;
import com.example.booking.payment.dto.PaymentMethod;
import com.example.booking.payment.dto.PaymentResult;
import com.example.booking.payment.entity.PaymentStatus;
import com.example.booking.payment.service.PaymentService;
import com.example.booking.support.AbstractIntegrationTest;
import com.example.booking.support.TestFixtures;
import com.example.booking.support.TestFixtures.EventFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerPaymentTest extends AbstractIntegrationTest {

    @Autowired HoldService holdService;
    @Autowired PaymentService paymentService;
    @Autowired LedgerPostings postings;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;

    private UUID hold(UUID user, EventFixture ev, int i) {
        return holdService.createHolds(user, new CreateHoldRequest(ev.eventId(), List.of(ev.eventSeatIds().get(i)))).get(0).id();
    }

    private PaymentResult pay(UUID user, UUID holdId, PaymentMethod method, String token) {
        return paymentService.pay(user, UUID.randomUUID().toString(), new PayRequest(List.of(holdId), method, token));
    }

    private UUID organizerOf(EventFixture ev) {
        return jdbc.queryForObject("SELECT organizer_id FROM events WHERE id = ?", UUID.class, ev.eventId());
    }

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    @Test
    void aCardPaymentCapturesIntoEscrowThenSettlesToOrganizerAndFee() {
        EventFixture ev = fixtures.createPublishedEvent(1, 3, 4);          // seats cost 500.00
        UUID user = fixtures.createUsers(1).get(0);

        PaymentResult r = pay(user, hold(user, ev, 0), PaymentMethod.CARD, "tok_success");
        UUID paymentId = r.payment().paymentId(), bookingId = r.payment().bookingId();
        UUID organizer = organizerOf(ev);

        assertThat(fixtures.ledgerEntries("PAYMENT_CAPTURE:" + paymentId))
                .containsExactlyInAnyOrder("DEBIT PROVIDER_CLEARING 500.00", "CREDIT ESCROW 500.00");
        assertThat(fixtures.ledgerEntries("BOOKING_SETTLEMENT:" + bookingId)).containsExactlyInAnyOrder(
                "DEBIT ESCROW 500.00", "CREDIT ORGANIZER_PAYABLE:" + organizer + " 475.00", "CREDIT PLATFORM_FEE_REVENUE 25.00");
        assertThat(fixtures.balanceOf("ORGANIZER_PAYABLE:" + organizer)).isEqualByComparingTo("475.00");
    }

    @Test
    void aDeclinedPaymentTouchesNoMoney() {
        EventFixture ev = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);
        PaymentResult r = pay(user, hold(user, ev, 0), PaymentMethod.CARD, "tok_declined");

        assertThat(r.payment().status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(fixtures.ledgerEntries("PAYMENT_CAPTURE:" + r.payment().paymentId())).isEmpty();
        assertThat(fixtures.ledgerEntries("BOOKING_SETTLEMENT:" + r.payment().bookingId())).isEmpty();
    }

    @Test
    void recoveryPostsTheLedgerExactlyOnceNoMatterHowOftenItRuns() {
        EventFixture ev = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);
        PaymentResult r = pay(user, hold(user, ev, 0), PaymentMethod.CARD, "tok_timeout_charged");
        String captureKey = "PAYMENT_CAPTURE:" + r.payment().paymentId();

        assertThat(fixtures.ledgerEntries(captureKey)).as("outcome unknown: no money recorded yet").isEmpty();
        paymentService.recoverStuckPayments();
        paymentService.recoverStuckPayments();

        assertThat(fixtures.ledgerEntries(captureKey)).hasSize(2);
        assertThat(fixtures.ledgerEntries("BOOKING_SETTLEMENT:" + r.payment().bookingId())).hasSize(3);
    }

    @Test
    void aWalletPaymentMovesMoneyFromTheWalletIntoEscrow() {
        EventFixture ev = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);
        fixtures.topUpWallet(user, "1000.00");

        PaymentResult r = pay(user, hold(user, ev, 0), PaymentMethod.WALLET, null);

        assertThat(r.payment().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(fixtures.ledgerEntries("PAYMENT_CAPTURE:" + r.payment().paymentId()))
                .containsExactlyInAnyOrder("DEBIT USER_WALLET:" + user + " 500.00", "CREDIT ESCROW 500.00");
        assertThat(fixtures.balanceOf("USER_WALLET:" + user)).isEqualByComparingTo("500.00");
    }

    @Test
    void aShortWalletRollsBackEverythingAndTheSameRequestWorksAfterATopUp() {
        EventFixture ev = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);
        UUID seat = ev.eventSeatIds().get(0);
        UUID holdId = hold(user, ev, 0);
        fixtures.topUpWallet(user, "100.00");
        String key = UUID.randomUUID().toString();
        PayRequest req = new PayRequest(List.of(holdId), PaymentMethod.WALLET, null);

        assertThatThrownBy(() -> paymentService.pay(user, key, req))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "INSUFFICIENT_BALANCE");

        assertThat(jdbc.queryForObject("SELECT status FROM seat_holds WHERE id = ?", String.class, holdId)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM event_seats WHERE id = ?", String.class, seat)).isEqualTo("HELD");
        assertThat(count("SELECT count(*) FROM bookings WHERE user_id = ?", user)).isZero();
        assertThat(fixtures.balanceOf("USER_WALLET:" + user)).isEqualByComparingTo("100.00");

        fixtures.topUpWallet(user, "1000.00");
        PaymentResult r = paymentService.pay(user, key, req);          // SAME key: the failed attempt consumed nothing

        assertThat(r.payment().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(fixtures.balanceOf("USER_WALLET:" + user)).isEqualByComparingTo("600.00");
    }

    @Test
    void tenParallelWalletSpendsCanNeverOverdrawTheWallet() throws Exception {
        EventFixture ev = fixtures.createPublishedEvent(1, 10, 10);    // 10 seats at 500, up to 10 per user
        UUID user = fixtures.createUsers(1).get(0);
        fixtures.topUpWallet(user, "2500.00");                          // enough for exactly 5 seats
        List<UUID> holdIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) holdIds.add(hold(user, ev, i));

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (UUID h : holdIds) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    pay(user, h, PaymentMethod.WALLET, null);
                    return true;
                } catch (ApiException e) {
                    if (e.getCode().equals("INSUFFICIENT_BALANCE")) return false;
                    throw e;
                }
            }));
        }
        start.countDown();
        int ok = 0, rejected = 0;
        for (Future<Boolean> f : futures) { if (f.get(120, TimeUnit.SECONDS)) ok++; else rejected++; }
        pool.shutdown();

        assertThat(ok).isEqualTo(5);
        assertThat(rejected).isEqualTo(5);
        assertThat(fixtures.balanceOf("USER_WALLET:" + user)).as("never negative").isEqualByComparingTo("0");
        assertThat(count("SELECT count(*) FROM bookings WHERE user_id = ? AND status = 'CONFIRMED'", user)).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM seat_holds WHERE user_id = ? AND status = 'ACTIVE'", user)).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM event_seats WHERE event_id = ? AND status = 'BOOKED'", ev.eventId())).isEqualTo(5);
    }

    @Test
    void reversingTheSettlementPutsTheOrganizersMoneyBack() {
        EventFixture ev = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);
        PaymentResult r = pay(user, hold(user, ev, 0), PaymentMethod.CARD, "tok_success");
        UUID organizer = organizerOf(ev);
        UUID settlementTx = jdbc.queryForObject("SELECT id FROM ledger_transactions WHERE idempotency_key = ?",
                UUID.class, "BOOKING_SETTLEMENT:" + r.payment().bookingId());

        postings.reverse(settlementTx, "REFUND:" + UUID.randomUUID(), "booking cancelled");

        assertThat(fixtures.balanceOf("ORGANIZER_PAYABLE:" + organizer)).isEqualByComparingTo("0");
    }
}