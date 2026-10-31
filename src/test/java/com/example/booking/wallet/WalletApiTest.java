package com.example.booking.wallet;

import com.example.booking.support.AbstractIntegrationTest;
import com.example.booking.support.TestUsers;
import com.example.booking.user.entity.RoleName;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WalletApiTest extends AbstractIntegrationTest {

    private static final String AUTH = "Authorization";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired TestUsers users;

    private String body(String amount, String token) throws Exception {
        return om.writeValueAsString(Map.of("amount", amount, "paymentMethodToken", token));
    }

    @Test
    void topUpIsCreditedOnceAndAReplayChangesNothing() throws Exception {
        String bearer = "Bearer " + users.tokenFor(RoleName.USER);
        String key = UUID.randomUUID().toString();

        mvc.perform(post("/api/wallet/topups").header(AUTH, bearer).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body("1000.00", "tok_success")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREDITED"))
                .andExpect(jsonPath("$.balance").value(1000.0));

        mvc.perform(post("/api/wallet/topups").header(AUTH, bearer).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body("1000.00", "tok_success")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.balance").value(1000.0));

        mvc.perform(get("/api/wallet").header(AUTH, bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(1000.0));
        mvc.perform(get("/api/ledger/transactions").header(AUTH, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].type").value("WALLET_TOPUP"))
                .andExpect(jsonPath("$.content[0].signedAmount").value(1000.0));
    }

    @Test
    void aDeclinedTopUpIs402AndCreditsNothing() throws Exception {
        String bearer = "Bearer " + users.tokenFor(RoleName.USER);
        mvc.perform(post("/api/wallet/topups").header(AUTH, bearer).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body("500.00", "tok_declined")))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value("DECLINED"));
        mvc.perform(get("/api/wallet").header(AUTH, bearer)).andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    void aTimedOutTopUpHealsWhenTheClientRetriesWithTheSameKey() throws Exception {
        String bearer = "Bearer " + users.tokenFor(RoleName.USER);
        String key = UUID.randomUUID().toString();

        // The provider charged us, but the response was lost.
        mvc.perform(post("/api/wallet/topups").header(AUTH, bearer).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body("750.00", "tok_timeout_charged")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get("/api/wallet").header(AUTH, bearer)).andExpect(jsonPath("$.balance").value(0));

        // Retry: the provider says "already charged", and the ledger credits exactly once.
        mvc.perform(post("/api/wallet/topups").header(AUTH, bearer).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body("750.00", "tok_timeout_charged")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(750.0));
    }

    @Test
    void theTrialBalanceIsAdminOnlyAndTheWholeLedgerBalances() throws Exception {
        mvc.perform(get("/api/ledger/trial-balance").header(AUTH, "Bearer " + users.tokenFor(RoleName.USER)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/ledger/trial-balance").header(AUTH, "Bearer " + users.tokenFor(RoleName.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(true));
    }
}