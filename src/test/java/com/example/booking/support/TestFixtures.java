package com.example.booking.support;

import com.example.booking.auth.security.AuthenticatedUser;
import com.example.booking.event.dto.CreateEventRequest;
import com.example.booking.event.service.EventService;
import com.example.booking.venue.dto.CreateVenueRequest;
import com.example.booking.venue.dto.SectionLayout;
import com.example.booking.venue.service.VenueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Test helper: builds realistic data fast (bulk SQL for users, real services for venue/event). */
@Component
public class TestFixtures {

    public record EventFixture(UUID eventId, List<UUID> eventSeatIds) {}

    @Autowired JdbcTemplate jdbc;
    @Autowired VenueService venueService;
    @Autowired EventService eventService;

    /** Bulk-inserts plain USERs. No BCrypt (that would take ~70 ms each), tests never log in with them. */
    public List<UUID> createUsers(int count) {
        List<UUID> ids = new ArrayList<>(count);
        List<Object[]> userRows = new ArrayList<>(count);
        List<Object[]> roleRows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            userRows.add(new Object[]{id, "bulk-" + id + "@example.com", "not-a-real-hash", "Bulk User " + i});
            roleRows.add(new Object[]{id, 1});   // role id 1 = USER (Flyway V1 seed)
        }
        jdbc.batchUpdate("INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, ?, ?)", userRows);
        jdbc.batchUpdate("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", roleRows);
        return ids;
    }

    public AuthenticatedUser createOrganizer() {
        UUID id = createUsers(1).get(0);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, 2)", id);   // 2 = ORGANIZER
        return new AuthenticatedUser(id, "organizer@test", Set.of("USER", "ORGANIZER"));
    }

    /** A PUBLISHED event whose sale window is open right now, with rows x seatsPerRow AVAILABLE seats. */
    public EventFixture createPublishedEvent(int rows, int seatsPerRow, int maxSeatsPerUser) {
        AuthenticatedUser organizer = createOrganizer();
        var venue = venueService.create(new CreateVenueRequest("Test Arena", "Bengaluru",
                List.of(new SectionLayout("A", rows, seatsPerRow))));
        Instant now = Instant.now();
        var event = eventService.create(organizer, new CreateEventRequest(
                venue.id(), "Flash Sale " + UUID.randomUUID(), null,
                now.plus(30, ChronoUnit.DAYS),
                now.minus(1, ChronoUnit.HOURS),      // sale already open
                now.plus(1, ChronoUnit.DAYS),
                maxSeatsPerUser, new BigDecimal("500.00")));
        eventService.publish(organizer, event.id());

        List<UUID> seatIds = jdbc.queryForList(
                "SELECT id FROM event_seats WHERE event_id = ? ORDER BY id", UUID.class, event.id());
        return new EventFixture(event.id(), seatIds);
    }
}
