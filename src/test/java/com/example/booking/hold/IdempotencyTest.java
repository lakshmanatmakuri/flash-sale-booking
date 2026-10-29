package com.example.booking.hold;

import com.example.booking.common.exception.ApiException;
import com.example.booking.hold.dto.CreateHoldRequest;
import com.example.booking.hold.dto.HoldResponse;
import com.example.booking.hold.service.HoldService;
import com.example.booking.support.AbstractIntegrationTest;
import com.example.booking.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyTest extends AbstractIntegrationTest {

    @Autowired HoldService holdService;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;

    private String key() { return UUID.randomUUID().toString(); }

    @Test
    void tenParallelIdenticalRequestsCreateExactlyOneHold() throws Exception {
        var fx = fixtures.createPublishedEvent(1, 5, 4);
        UUID user = fixtures.createUsers(1).get(0);
        String key = key();
        var req = new CreateHoldRequest(fx.eventId(), List.of(fx.eventSeatIds().get(0)));

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<HoldResponse>>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return holdService.createHoldsIdempotent(user, key, req);
            }));
        }
        start.countDown();

        Set<UUID> holdIds = new HashSet<>();
        for (Future<List<HoldResponse>> f : futures) {
            f.get(60, TimeUnit.SECONDS).forEach(h -> holdIds.add(h.id()));
        }
        pool.shutdown();

        assertThat(holdIds).as("every duplicate got the SAME hold back").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seat_holds WHERE user_id = ?", Integer.class, user))
                .isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        var fx = fixtures.createPublishedEvent(1, 5, 4);
        UUID user = fixtures.createUsers(1).get(0);
        String key = key();
        holdService.createHoldsIdempotent(user, key,
                new CreateHoldRequest(fx.eventId(), List.of(fx.eventSeatIds().get(0))));

        assertThatThrownBy(() -> holdService.createHoldsIdempotent(user, key,
                new CreateHoldRequest(fx.eventId(), List.of(fx.eventSeatIds().get(1)))))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void aFailedAttemptDoesNotConsumeTheKey() {
        var fx = fixtures.createPublishedEvent(1, 5, 4);
        List<UUID> users = fixtures.createUsers(2);
        UUID alice = users.get(0), bob = users.get(1);
        var req = new CreateHoldRequest(fx.eventId(), List.of(fx.eventSeatIds().get(0)));
        String bobKey = key();

        HoldResponse aliceHold = holdService.createHoldsIdempotent(alice, key(), req).get(0);

        // Bob loses the seat. His whole transaction, including his key row, rolls back.
        assertThatThrownBy(() -> holdService.createHoldsIdempotent(bob, bobKey, req))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SEAT_NOT_AVAILABLE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_keys WHERE user_id = ? AND idem_key = ?", Integer.class, bob, bobKey))
                .isZero();

        holdService.release(alice, aliceHold.id());

        // Same key, same body: it works now, because the failed attempt left nothing behind.
        assertThat(holdService.createHoldsIdempotent(bob, bobKey, req)).hasSize(1);
    }

    @Test
    void theSameKeyStringFromTwoUsersDoesNotCollide() {
        var fx = fixtures.createPublishedEvent(1, 5, 4);
        List<UUID> users = fixtures.createUsers(2);
        String sharedKey = key();

        var a = holdService.createHoldsIdempotent(users.get(0), sharedKey,
                new CreateHoldRequest(fx.eventId(), List.of(fx.eventSeatIds().get(0))));
        var b = holdService.createHoldsIdempotent(users.get(1), sharedKey,
                new CreateHoldRequest(fx.eventId(), List.of(fx.eventSeatIds().get(1))));

        assertThat(a.get(0).id()).isNotEqualTo(b.get(0).id());
    }
}
