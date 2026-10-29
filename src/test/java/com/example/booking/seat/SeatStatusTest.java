package com.example.booking.seat;

import com.example.booking.seat.entity.SeatStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.example.booking.seat.entity.SeatStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatStatusTest {

    private static final Set<List<SeatStatus>> LEGAL = Set.of(
            List.of(AVAILABLE, HELD),
            List.of(HELD, AVAILABLE), List.of(HELD, PAYMENT_PENDING),
            List.of(PAYMENT_PENDING, BOOKED), List.of(PAYMENT_PENDING, AVAILABLE),
            List.of(BOOKED, CANCELLED),
            List.of(CANCELLED, AVAILABLE));

    @Test
    void exactlyTheDocumentedTransitionsAreLegal() {
        for (SeatStatus from : values()) {
            for (SeatStatus to : values()) {
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(LEGAL.contains(List.of(from, to)));
            }
        }
    }

    @Test
    void skippingPaymentIsABug() {
        assertThatThrownBy(() -> AVAILABLE.requireTransitionTo(BOOKED))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> HELD.requireTransitionTo(BOOKED))
                .isInstanceOf(IllegalStateException.class);
    }
}