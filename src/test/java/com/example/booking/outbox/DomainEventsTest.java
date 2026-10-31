package com.example.booking.outbox;

import com.example.booking.hold.dto.CreateHoldRequest;
import com.example.booking.hold.service.HoldService;
import com.example.booking.payment.dto.PayRequest;
import com.example.booking.payment.dto.PaymentMethod;
import com.example.booking.payment.dto.PaymentResult;
import com.example.booking.payment.service.PaymentService;
import com.example.booking.support.AbstractIntegrationTest;
import com.example.booking.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DomainEventsTest extends AbstractIntegrationTest {

    @Autowired HoldService holdService;
    @Autowired PaymentService paymentService;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;

    private List<String> types(UUID aggregateId) {
        return jdbc.queryForList("SELECT event_type FROM outbox_events WHERE aggregate_id = ? ORDER BY seq",
                String.class, aggregateId.toString());
    }

    private UUID hold(UUID user, UUID eventId, UUID seat) {
        return holdService.createHolds(user, new CreateHoldRequest(eventId, List.of(seat))).get(0).id();
    }

    @Test
    void holdReleaseAndExpiryEmitTheirEvents() {
        var fx = fixtures.createPublishedEvent(1, 3, 4);
        List<UUID> users = fixtures.createUsers(2);
        UUID seat = fx.eventSeatIds().get(0);

        UUID h1 = hold(users.get(0), fx.eventId(), seat);
        assertThat(types(seat)).containsExactly("SeatHeld");

        holdService.release(users.get(0), h1);
        assertThat(types(seat)).containsExactly("SeatHeld", "SeatReleased");
        assertThat(jdbc.queryForObject("SELECT payload->>'reason' FROM outbox_events WHERE aggregate_id = ? AND event_type = 'SeatReleased'",
                String.class, seat.toString())).isEqualTo("HOLD_RELEASED");

        UUID h2 = hold(users.get(1), fx.eventId(), seat);
        jdbc.update("UPDATE seat_holds SET expires_at = now() - interval '1 second' WHERE id = ?", h2);
        holdService.expireDueBatch();

        assertThat(types(seat)).containsExactly("SeatHeld", "SeatReleased", "SeatHeld", "SeatHoldExpired", "SeatReleased");
    }

    @Test
    void aConfirmedBookingEmitsBookingConfirmed() {
        var fx = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);
        UUID holdId = hold(user, fx.eventId(), fx.eventSeatIds().get(0));

        PaymentResult r = paymentService.pay(user, UUID.randomUUID().toString(),
                new PayRequest(List.of(holdId), PaymentMethod.CARD, "tok_success"));

        assertThat(types(r.payment().bookingId())).containsExactly("BookingConfirmed");
        assertThat(types(r.payment().paymentId())).isEmpty();
    }

    @Test
    void aDeclinedPaymentEmitsPaymentFailedAndSeatReleased() {
        var fx = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);
        UUID seat = fx.eventSeatIds().get(0);
        UUID holdId = hold(user, fx.eventId(), seat);

        PaymentResult r = paymentService.pay(user, UUID.randomUUID().toString(),
                new PayRequest(List.of(holdId), PaymentMethod.CARD, "tok_declined"));

        assertThat(types(r.payment().paymentId())).containsExactly("PaymentFailed");
        assertThat(types(seat)).containsExactly("SeatHeld", "SeatReleased");
        assertThat(jdbc.queryForObject("SELECT payload->>'reason' FROM outbox_events WHERE aggregate_id = ? AND event_type = 'SeatReleased'",
                String.class, seat.toString())).isEqualTo("PAYMENT_FAILED");
        assertThat(types(r.payment().bookingId())).as("no BookingConfirmed for a failed payment").isEmpty();
    }
}
