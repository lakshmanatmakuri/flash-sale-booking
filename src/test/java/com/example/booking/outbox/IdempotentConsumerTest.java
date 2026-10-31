package com.example.booking.outbox;

import com.example.booking.infrastructure.messaging.IdempotentConsumerService;
import com.example.booking.notification.NotificationService;
import com.example.booking.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotentConsumerTest extends AbstractIntegrationTest {

    @Autowired IdempotentConsumerService idempotent;
    @Autowired NotificationService notifications;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aDuplicateDeliveryRunsTheHandlerOnce() {
        UUID messageId = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();

        assertThat(idempotent.process("test", messageId, runs::incrementAndGet)).isTrue();
        assertThat(idempotent.process("test", messageId, runs::incrementAndGet)).isFalse();
        assertThat(runs).hasValue(1);
        assertThat(idempotent.process("other-consumer", messageId, runs::incrementAndGet))
                .as("dedupe is per consumer").isTrue();
    }

    @Test
    void tenConcurrentDeliveriesOfTheSameMessageRunTheHandlerOnce() throws Exception {
        UUID messageId = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return idempotent.process("test", messageId, runs::incrementAndGet);
            }));
        }
        start.countDown();
        for (Future<Boolean> f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(runs).hasValue(1);
    }

    @Test
    void aFailingHandlerLeavesNoMarkerSoTheRedeliveryIsProcessed() {
        UUID messageId = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> idempotent.process("test", messageId, () -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE message_id = ?", Integer.class, messageId))
                .as("marker rolled back with the failed handler").isZero();

        assertThat(idempotent.process("test", messageId, runs::incrementAndGet)).isTrue();
        assertThat(runs).hasValue(1);
    }

    @Test
    void theNotificationConsumerIgnoresADuplicateEnvelope() {
        UUID messageId = UUID.randomUUID();
        String envelope = """
                {"messageId":"%s","type":"BookingConfirmed","occurredAt":"2026-01-01T00:00:00Z",
                 "payload":{"bookingId":"%s","userId":"%s"}}
                """.formatted(messageId, UUID.randomUUID(), UUID.randomUUID());

        assertThat(notifications.handle(envelope)).isTrue();
        assertThat(notifications.handle(envelope)).as("Kafka redelivered the same message").isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE consumer = 'notification' AND message_id = ?",
                Integer.class, messageId)).isEqualTo(1);
    }
}