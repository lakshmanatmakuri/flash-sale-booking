package com.example.booking.hold;

import com.example.booking.support.AbstractIntegrationTest;
import com.example.booking.support.TestFixtures;
import com.example.booking.support.TestUsers;
import com.example.booking.user.entity.RoleName;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HoldApiTest extends AbstractIntegrationTest {

    private static final String AUTH = "Authorization";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired TestUsers users;
    @Autowired TestFixtures fixtures;

    private String body(UUID eventId, UUID... seatIds) throws Exception {
        return om.writeValueAsString(Map.of("eventId", eventId, "eventSeatIds", List.of(seatIds)));
    }

    @Test
    void holdLifecycleOverHttp() throws Exception {
        var fx = fixtures.createPublishedEvent(1, 5, 4);
        UUID seat = fx.eventSeatIds().get(0);
        String alice = "Bearer " + users.tokenFor(RoleName.USER);
        String bob = "Bearer " + users.tokenFor(RoleName.USER);

        mvc.perform(post("/api/holds").contentType(MediaType.APPLICATION_JSON).content(body(fx.eventId(), seat)))
                .andExpect(status().isUnauthorized());

        String json = mvc.perform(post("/api/holds").header(AUTH, alice)
                        .contentType(MediaType.APPLICATION_JSON).content(body(fx.eventId(), seat)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].eventSeatId").value(seat.toString()))
                .andReturn().getResponse().getContentAsString();
        String holdId = om.readTree(json).get(0).get("id").asText();

        mvc.perform(post("/api/holds").header(AUTH, bob)
                        .contentType(MediaType.APPLICATION_JSON).content(body(fx.eventId(), seat)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_NOT_AVAILABLE"));

        mvc.perform(get("/api/holds").header(AUTH, alice))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));

        mvc.perform(delete("/api/holds/" + holdId).header(AUTH, bob)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/holds/" + holdId).header(AUTH, alice)).andExpect(status().isNoContent());

        // the seat is free again, so Bob can take it
        mvc.perform(post("/api/holds").header(AUTH, bob)
                        .contentType(MediaType.APPLICATION_JSON).content(body(fx.eventId(), seat)))
                .andExpect(status().isCreated());
    }

    @Test
    void emptySeatListIsRejected() throws Exception {
        var fx = fixtures.createPublishedEvent(1, 2, 4);
        String alice = "Bearer " + users.tokenFor(RoleName.USER);
        mvc.perform(post("/api/holds").header(AUTH, alice).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("eventId", fx.eventId(), "eventSeatIds", List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
