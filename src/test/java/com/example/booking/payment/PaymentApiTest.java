package com.example.booking.payment;

import com.example.booking.auth.security.JwtService;
import com.example.booking.hold.dto.CreateHoldRequest;
import com.example.booking.hold.service.HoldService;
import com.example.booking.support.AbstractIntegrationTest;
import com.example.booking.support.TestFixtures;
import com.example.booking.support.TestFixtures.EventFixture;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentApiTest extends AbstractIntegrationTest {

    private static final String AUTH = "Authorization";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired TestUsers users;
    @Autowired TestFixtures fixtures;
    @Autowired JwtService jwt;
    @Autowired HoldService holdService;

    private String payBody(UUID holdId, String token) throws Exception {
        return om.writeValueAsString(Map.of("holdIds", List.of(holdId), "method", "CARD", "paymentMethodToken", token));
    }

    private UUID holdFor(String rawJwt, EventFixture fx, int seatIndex) {
        return holdService.createHolds(jwt.parse(rawJwt).id(),
                new CreateHoldRequest(fx.eventId(), List.of(fx.eventSeatIds().get(seatIndex)))).get(0).id();
    }

    @Test
    void successThenReplayThenFetch() throws Exception {
        var fx = fixtures.createPublishedEvent(1, 3, 4);
        String rawJwt = users.tokenFor(RoleName.USER);
        String bearer = "Bearer " + rawJwt;
        UUID holdId = holdFor(rawJwt, fx, 0);
        String key = UUID.randomUUID().toString();

        String json = mvc.perform(post("/api/payments").header(AUTH, bearer).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(payBody(holdId, "tok_success")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andReturn().getResponse().getContentAsString();
        String paymentId = om.readTree(json).get("paymentId").asText();
        String bookingId = om.readTree(json).get("bookingId").asText();

        // Same request again: same payment, flagged as a replay, no second charge
        mvc.perform(post("/api/payments").header(AUTH, bearer).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(payBody(holdId, "tok_success")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.paymentId").value(paymentId));

        mvc.perform(get("/api/payments/" + paymentId).header(AUTH, bearer)).andExpect(status().isOk());
        mvc.perform(get("/api/bookings/" + bookingId).header(AUTH, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.items.length()").value(1));

        // Someone else's payment looks like it does not exist
        mvc.perform(get("/api/payments/" + paymentId).header(AUTH, "Bearer " + users.tokenFor(RoleName.USER)))
                .andExpect(status().isNotFound());

        // The header is mandatory
        mvc.perform(post("/api/payments").header(AUTH, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(payBody(holdId, "tok_success")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
    }

    @Test
    void declinedCardIs402AndAnUnknownOutcomeIs202() throws Exception {
        var fx = fixtures.createPublishedEvent(1, 3, 4);
        String rawJwt = users.tokenFor(RoleName.USER);
        String bearer = "Bearer " + rawJwt;

        mvc.perform(post("/api/payments").header(AUTH, bearer).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(payBody(holdFor(rawJwt, fx, 0), "tok_declined")))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("card_declined"));

        mvc.perform(post("/api/payments").header(AUTH, bearer).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(payBody(holdFor(rawJwt, fx, 1), "tok_timeout_charged")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("UNKNOWN"));
    }
}