package com.example.booking.event;

import com.example.booking.support.AbstractIntegrationTest;
import com.example.booking.support.TestUsers;
import com.example.booking.user.entity.RoleName;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CatalogIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired TestUsers users;
    @Autowired JdbcTemplate jdbc;

    private static final String AUTH = "Authorization";

    private String venueBody() throws Exception {
        return om.writeValueAsString(Map.of("name", "Arena", "city", "Bengaluru", "sections", List.of(
                Map.of("name", "A", "rows", 10, "seatsPerRow", 25),
                Map.of("name", "B", "rows", 10, "seatsPerRow", 25))));
    }

    private String eventBody(String venueId) throws Exception {
        Instant now = Instant.now();
        return om.writeValueAsString(Map.of(
                "venueId", venueId, "name", "Flash Concert",
                "startsAt", now.plus(30, ChronoUnit.DAYS).toString(),
                "saleStartsAt", now.plus(1, ChronoUnit.DAYS).toString(),
                "saleEndsAt", now.plus(2, ChronoUnit.DAYS).toString(),
                "maxSeatsPerUser", 4, "basePrice", "500.00"));
    }

    @Test
    void organizerCreatesVenueAndEventThenPublishes() throws Exception {
        String organizer = "Bearer " + users.tokenFor(RoleName.ORGANIZER);

        String venueJson = mvc.perform(post("/api/venues").header(AUTH, organizer)
                        .contentType(MediaType.APPLICATION_JSON).content(venueBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalSeats").value(500))
                .andReturn().getResponse().getContentAsString();
        String venueId = om.readTree(venueJson).get("id").asText();

        String eventJson = mvc.perform(post("/api/events").header(AUTH, organizer)
                        .contentType(MediaType.APPLICATION_JSON).content(eventBody(venueId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.availableSeats").value(500))
                .andReturn().getResponse().getContentAsString();
        String eventId = om.readTree(eventJson).get("id").asText();

        // The inventory really exists in the database, all AVAILABLE
        Integer rows = jdbc.queryForObject(
                "select count(*) from event_seats where event_id = ? and status = 'AVAILABLE'",
                Integer.class, UUID.fromString(eventId));
        assertThat(rows).isEqualTo(500);

        // A DRAFT is invisible to the public
        mvc.perform(get("/api/events/" + eventId)).andExpect(status().isNotFound());

        // Publish, then it is public
        mvc.perform(post("/api/events/" + eventId + "/publish").header(AUTH, organizer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
        mvc.perform(get("/api/events/" + eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableSeats").value(500));

        // Publishing twice is a state conflict
        mvc.perform(post("/api/events/" + eventId + "/publish").header(AUTH, organizer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_EVENT_STATE"));

        // Seat map needs a login, then returns all 500 seats
        mvc.perform(get("/api/events/" + eventId + "/seats")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/events/" + eventId + "/seats").header(AUTH, organizer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(500))
                .andExpect(jsonPath("$[0].section").value("A"))
                .andExpect(jsonPath("$[0].row").value("A"))
                .andExpect(jsonPath("$[0].seatNumber").value(1));
    }

    @Test
    void plainUserCannotCreateVenueOrEvent() throws Exception {
        String user = "Bearer " + users.tokenFor(RoleName.USER);
        mvc.perform(post("/api/venues").header(AUTH, user)
                        .contentType(MediaType.APPLICATION_JSON).content(venueBody()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/venues").contentType(MediaType.APPLICATION_JSON).content(venueBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anotherOrganizerCannotPublishSomeoneElsesEvent() throws Exception {
        String owner = "Bearer " + users.tokenFor(RoleName.ORGANIZER);
        String other = "Bearer " + users.tokenFor(RoleName.ORGANIZER);

        String venueId = om.readTree(mvc.perform(post("/api/venues").header(AUTH, owner)
                        .contentType(MediaType.APPLICATION_JSON).content(venueBody()))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        String eventId = om.readTree(mvc.perform(post("/api/events").header(AUTH, owner)
                        .contentType(MediaType.APPLICATION_JSON).content(eventBody(venueId)))
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        mvc.perform(post("/api/events/" + eventId + "/publish").header(AUTH, other))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidSaleWindowIsRejected() throws Exception {
        String organizer = "Bearer " + users.tokenFor(RoleName.ORGANIZER);
        String venueId = om.readTree(mvc.perform(post("/api/venues").header(AUTH, organizer)
                        .contentType(MediaType.APPLICATION_JSON).content(venueBody()))
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        Instant now = Instant.now();
        String bad = om.writeValueAsString(Map.of(
                "venueId", venueId, "name", "Bad",
                "startsAt", now.plus(30, ChronoUnit.DAYS).toString(),
                "saleStartsAt", now.plus(5, ChronoUnit.DAYS).toString(),
                "saleEndsAt", now.plus(2, ChronoUnit.DAYS).toString(),   // ends before it starts
                "basePrice", "500.00"));
        mvc.perform(post("/api/events").header(AUTH, organizer)
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
}