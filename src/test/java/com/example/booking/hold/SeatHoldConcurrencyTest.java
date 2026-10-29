package com.example.booking.hold;

import com.example.booking.common.exception.ApiException;
import com.example.booking.common.exception.ConflictException;
import com.example.booking.common.exception.ResourceNotFoundException;
import com.example.booking.hold.dto.CreateHoldRequest;
import com.example.booking.hold.service.HoldService;
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

class SeatHoldConcurrencyTest extends AbstractIntegrationTest {

    @Autowired HoldService holdService;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;

    private record Result(int successes, int rejected, List<Throwable> unexpected) {}

    /** Runs all tasks on a bounded pool, released by one starting gun. Expected business rejections count as `rejected`. */
    private Result runConcurrently(int poolSize, List<Callable<Boolean>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (Callable<Boolean> t : tasks) {
            futures.add(pool.submit(() -> { start.await(); return t.call(); }));
        }
        start.countDown();

        int ok = 0, rejected = 0;
        List<Throwable> unexpected = new ArrayList<>();
        for (Future<Boolean> f : futures) {
            try {
                if (f.get(120, TimeUnit.SECONDS)) ok++; else rejected++;
            } catch (ExecutionException e) {
                unexpected.add(e.getCause());
            }
        }
        pool.shutdown();
        return new Result(ok, rejected, unexpected);
    }

    private Callable<Boolean> attempt(UUID user, UUID eventId, List<UUID> seats) {
        return () -> {
            try {
                holdService.createHolds(user, new CreateHoldRequest(eventId, seats));
                return true;
            } catch (ApiException e) {
                if (e.getCode().equals("SEAT_NOT_AVAILABLE") || e.getCode().equals("HOLD_LIMIT_EXCEEDED")) {
                    return false;               // an expected, clean rejection
                }
                throw e;
            }
        };
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    // ---------------------------------------------------------------------------------------------------------

    @Test
    void thousandUsersFightForTenSeats_exactlyTenWin() throws Exception {
        EventFixture fx = fixtures.createPublishedEvent(1, 10, 4);          // 10 seats
        List<UUID> users = fixtures.createUsers(1000);

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < users.size(); i++) {
            // 100 users fight for each of the 10 seats
            tasks.add(attempt(users.get(i), fx.eventId(), List.of(fx.eventSeatIds().get(i % 10))));
        }
        Result r = runConcurrently(100, tasks);

        assertThat(r.unexpected()).as("no deadlocks, timeouts or surprises").isEmpty();
        assertThat(r.successes()).isEqualTo(10);
        assertThat(r.rejected()).isEqualTo(990);

        // Assert on the DATABASE, not just on what the code claims:
        assertThat(count("SELECT count(*) FROM seat_holds WHERE event_id = ? AND status = 'ACTIVE'", fx.eventId()))
                .isEqualTo(10);
        assertThat(count("SELECT count(DISTINCT event_seat_id) FROM seat_holds WHERE event_id = ? AND status = 'ACTIVE'",
                fx.eventId())).as("no seat held twice").isEqualTo(10);
        assertThat(count("SELECT count(*) FROM event_seats WHERE event_id = ? AND status = 'HELD'", fx.eventId()))
                .isEqualTo(10);
        assertThat(count("SELECT count(*) FROM event_seats WHERE event_id = ? AND status = 'AVAILABLE'", fx.eventId()))
                .isZero();
    }

    @Test
    void multiSeatHoldsInOppositeOrderNeitherDeadlockNorSplit() throws Exception {
        EventFixture fx = fixtures.createPublishedEvent(1, 2, 4);           // exactly 2 seats
        UUID a = fx.eventSeatIds().get(0), b = fx.eventSeatIds().get(1);
        List<UUID> users = fixtures.createUsers(200);

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < users.size(); i++) {
            // half ask for (a, b), half for (b, a): the classic deadlock recipe
            List<UUID> seats = (i % 2 == 0) ? List.of(a, b) : List.of(b, a);
            tasks.add(attempt(users.get(i), fx.eventId(), seats));
        }
        Result r = runConcurrently(100, tasks);

        assertThat(r.unexpected()).as("a Postgres deadlock would show up here").isEmpty();
        assertThat(r.successes()).isEqualTo(1);
        assertThat(r.rejected()).isEqualTo(199);
        // all-or-nothing: the single winner owns BOTH seats, nobody owns half
        assertThat(count("SELECT count(*) FROM seat_holds WHERE event_id = ? AND status = 'ACTIVE'", fx.eventId()))
                .isEqualTo(2);
        assertThat(count("SELECT count(DISTINCT user_id) FROM seat_holds WHERE event_id = ? AND status = 'ACTIVE'",
                fx.eventId())).isEqualTo(1);
    }

    @Test
    void oneUserFiringParallelRequestsCannotExceedTheSeatLimit() throws Exception {
        EventFixture fx = fixtures.createPublishedEvent(1, 20, 4);          // limit: 4 seats per user
        UUID user = fixtures.createUsers(1).get(0);

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (UUID seat : fx.eventSeatIds()) {                                // 20 parallel requests, 20 different seats
            tasks.add(attempt(user, fx.eventId(), List.of(seat)));
        }
        Result r = runConcurrently(20, tasks);

        assertThat(r.unexpected()).isEmpty();
        assertThat(r.successes()).isEqualTo(4);
        assertThat(r.rejected()).isEqualTo(16);
        assertThat(count("SELECT count(*) FROM seat_holds WHERE user_id = ? AND status = 'ACTIVE'", user)).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM event_seats WHERE event_id = ? AND status = 'HELD'", fx.eventId()))
                .isEqualTo(4);
    }

    @Test
    void expiredHoldsAreSweptAndTheSeatReturnsToThePool() {
        EventFixture fx = fixtures.createPublishedEvent(1, 3, 4);
        List<UUID> users = fixtures.createUsers(2);
        UUID seat = fx.eventSeatIds().get(0);

        var hold = holdService.createHolds(users.get(0), new CreateHoldRequest(fx.eventId(), List.of(seat))).get(0);

        // Fast-forward time by editing the deadline instead of sleeping for 5 minutes
        jdbc.update("UPDATE seat_holds SET expires_at = now() - interval '1 second' WHERE id = ?", hold.id());

        assertThat(holdService.expireDueBatch()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM seat_holds WHERE id = ?", String.class, hold.id()))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM event_seats WHERE id = ?", String.class, seat))
                .isEqualTo("AVAILABLE");

        // and somebody else can now take it; an expired hold cannot be renewed
        assertThat(holdService.createHolds(users.get(1), new CreateHoldRequest(fx.eventId(), List.of(seat)))).hasSize(1);
        assertThatThrownBy(() -> holdService.renew(users.get(0), hold.id()))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "HOLD_EXPIRED");
    }

    @Test
    void renewOnlyOnceAndReleaseIsOwnerOnlyAndIdempotent() {
        EventFixture fx = fixtures.createPublishedEvent(1, 3, 4);
        List<UUID> users = fixtures.createUsers(2);
        UUID seat = fx.eventSeatIds().get(0);
        var hold = holdService.createHolds(users.get(0), new CreateHoldRequest(fx.eventId(), List.of(seat))).get(0);

        assertThat(holdService.renew(users.get(0), hold.id()).renewalCount()).isEqualTo(1);
        assertThatThrownBy(() -> holdService.renew(users.get(0), hold.id()))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "HOLD_RENEWAL_LIMIT");

        assertThatThrownBy(() -> holdService.release(users.get(1), hold.id()))      // not your hold
                .isInstanceOf(ResourceNotFoundException.class);

        holdService.release(users.get(0), hold.id());
        holdService.release(users.get(0), hold.id());                               // second call: harmless no-op
        assertThat(jdbc.queryForObject("SELECT status FROM event_seats WHERE id = ?", String.class, seat))
                .isEqualTo("AVAILABLE");
    }

    @Test
    void databaseRefusesASecondActiveHoldEvenIfApplicationCodeIsBypassed() {
        // Layer 2 of the defense: pretend a future bug skipped the guarded UPDATE.
        EventFixture fx = fixtures.createPublishedEvent(1, 1, 4);
        List<UUID> users = fixtures.createUsers(2);
        UUID seat = fx.eventSeatIds().get(0);
        holdService.createHolds(users.get(0), new CreateHoldRequest(fx.eventId(), List.of(seat)));

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO seat_holds (event_seat_id, event_id, user_id, locked_price, expires_at)
                VALUES (?, ?, ?, 500, now() + interval '5 minutes')
                """, seat, fx.eventId(), users.get(1)))
                .hasMessageContaining("uq_seat_holds_one_active_per_seat");
    }
}
