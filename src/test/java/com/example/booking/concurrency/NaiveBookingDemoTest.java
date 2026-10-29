
package com.example.booking.concurrency;

import com.example.booking.support.AbstractIntegrationTest;
import com.example.booking.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE "BEFORE" PICTURE. This test PASSES when the naive algorithm is BROKEN.
 * It stays in the repo as living documentation of why Phase 5 exists.
 */
class NaiveBookingDemoTest extends AbstractIntegrationTest {

    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void naiveCheckThenActHandsOutTheSameSeatToManyUsers() throws Exception {
        jdbc.execute("CREATE TABLE IF NOT EXISTS naive_claims (event_seat_id UUID NOT NULL, user_id UUID NOT NULL)");
        jdbc.execute("TRUNCATE naive_claims");
        try {
            List<UUID> seats = fixtures.createPublishedEvent(1, 10, 4).eventSeatIds();   // 10 seats
            TransactionTemplate tx = new TransactionTemplate(txManager);

            int attempts = 1000;
            AtomicInteger successes = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(100);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < attempts; i++) {
                UUID seat = seats.get(i % seats.size());     // 100 users fight for each seat
                UUID user = UUID.randomUUID();
                futures.add(pool.submit(() -> {
                    start.await();                            // release everyone at the same instant
                    if (Boolean.TRUE.equals(tx.execute(s -> naiveClaim(seat, user)))) {
                        successes.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
            pool.shutdown();

            Integer doubleBookedSeats = jdbc.queryForObject("""
                    SELECT count(*) FROM (
                        SELECT event_seat_id FROM naive_claims
                        GROUP BY event_seat_id HAVING count(*) > 1) t
                    """, Integer.class);

            System.out.printf("%n=== NAIVE RESULT: %d 'successful' bookings for 10 seats; %d seats double-booked ===%n%n",
                    successes.get(), doubleBookedSeats);

            assertThat(successes.get()).as("more successes than seats = the bug").isGreaterThan(10);
            assertThat(doubleBookedSeats).isGreaterThan(0);
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS naive_claims");
        }
    }

    /** check-then-act, exactly as described above. The sleep stands in for real work between the two steps. */
    private boolean naiveClaim(UUID seatId, UUID userId) {
        String status = jdbc.queryForObject("SELECT status FROM event_seats WHERE id = ?", String.class, seatId);
        if (!"AVAILABLE".equals(status)) return false;

        try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        jdbc.update("UPDATE event_seats SET status = 'HELD' WHERE id = ?", seatId);          // NOT guarded
        jdbc.update("INSERT INTO naive_claims (event_seat_id, user_id) VALUES (?, ?)", seatId, userId);
        return true;
    }
}