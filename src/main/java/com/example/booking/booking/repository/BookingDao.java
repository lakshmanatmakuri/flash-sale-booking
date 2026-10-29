package com.example.booking.booking.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Guarded booking transitions. "true / present" means THIS call made the transition. */
@Repository
@RequiredArgsConstructor
public class BookingDao {

    private final NamedParameterJdbcTemplate jdbc;

    /** PENDING_PAYMENT -> CONFIRMED. Returns the event id so the caller can look up the organizer. */
    public Optional<UUID> confirm(UUID bookingId) {
        return jdbc.queryForList("""
                UPDATE bookings
                   SET status = 'CONFIRMED', confirmed_at = now(), version = version + 1, updated_at = now()
                 WHERE id = :id AND status = 'PENDING_PAYMENT'
                RETURNING event_id
                """, Map.of("id", bookingId), UUID.class).stream().findFirst();
    }

    /** PENDING_PAYMENT -> FAILED. */
    public boolean fail(UUID bookingId) {
        return jdbc.update("""
                UPDATE bookings SET status = 'FAILED', version = version + 1, updated_at = now()
                 WHERE id = :id AND status = 'PENDING_PAYMENT'
                """, Map.of("id", bookingId)) == 1;
    }

    public int setItemsStatus(UUID bookingId, String from, String to) {
        return jdbc.update("""
                UPDATE booking_items SET status = :to, updated_at = now()
                 WHERE booking_id = :id AND status = :from
                """, Map.of("id", bookingId, "from", from, "to", to));
    }
}