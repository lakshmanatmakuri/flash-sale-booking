package com.example.booking.outbox;

import com.example.booking.hold.dto.CreateHoldRequest;
import com.example.booking.hold.service.HoldService;
import com.example.booking.infrastructure.outbox.OutboxDao;
import com.example.booking.infrastructure.outbox.OutboxProperties;
import com.example.booking.infrastructure.outbox.OutboxPublisher;
import com.example.booking.infrastructure.outbox.OutboxService;
import com.example.booking.support.AbstractIntegrationTest;
import com.example.booking.support.TestFixtures;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class OutboxIntegrationTest extends AbstractIntegrationTest {

    @Autowired HoldService holdService;
    @Autowired OutboxService outbox;
    @Autowired OutboxPublisher publisher;
    @Autowired OutboxDao outboxDao;
    @Autowired OutboxProperties props;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired KafkaContainer kafka;

    /** Events left over from other test classes must not influence the assertions here. */
    @BeforeEach
    void clearBacklog() {
        jdbc.update("UPDATE outbox_events SET status = 'SENT', sent_at = now() WHERE status = 'PENDING'");
    }

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    private UUID holdSeat(UUID user, TestFixtures.EventFixture fx, int index) {
        UUID seat = fx.eventSeatIds().get(index);
        holdService.createHolds(user, new CreateHoldRequest(fx.eventId(), List.of(seat)));
        return seat;
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        return new String(r.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private List<ConsumerRecord<String, String>> consumeUntil(Predicate<List<ConsumerRecord<String, String>>> done) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "probe-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<ConsumerRecord<String, String>> all = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
            consumer.subscribe(List.of(props.topic()));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline && !done.test(all)) {
                consumer.poll(Duration.ofMillis(500)).forEach(all::add);
            }
        }
        return all;
    }

    // -----------------------------------------------------------------------------------------------------

    @Test
    void theEventCommitsAndRollsBackWithTheBusinessTransaction() {
        var fx = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);
        UUID seat = holdSeat(user, fx, 0);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'SeatHeld' AND status = 'PENDING'",
                seat.toString())).as("committed together with the hold").isEqualTo(1);

        TransactionTemplate tx = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            outbox.record("SEAT", "rollback-probe", "SeatHeld", Map.of("x", 1));
            throw new IllegalStateException("business step failed after the event was written");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = 'rollback-probe'"))
                .as("rolled back together with the business change").isZero();

        assertThatThrownBy(() -> outbox.record("SEAT", "no-tx", "SeatHeld", Map.of()))
                .as("an event can only be written inside a transaction").isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void publishedEventsReachKafkaWithTheSeatAsKeyAndTheMessageIdAsHeader() {
        var fx = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);
        UUID seat = holdSeat(user, fx, 0);

        assertThat(publisher.publishDueBatch()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND status = 'SENT'", seat.toString())).isEqualTo(1);

        String messageId = jdbc.queryForObject("SELECT CAST(id AS text) FROM outbox_events WHERE aggregate_id = ?",
                String.class, seat.toString());
        var got = consumeUntil(all -> all.stream().anyMatch(r -> seat.toString().equals(r.key())));
        var record = got.stream().filter(r -> seat.toString().equals(r.key())).findFirst().orElseThrow();
        assertThat(header(record, "event-type")).isEqualTo("SeatHeld");
        assertThat(header(record, "message-id")).isEqualTo(messageId);
        assertThat(record.value()).contains("\"type\":\"SeatHeld\"").contains(messageId);
    }

    @Test
    void kafkaDown_businessKeepsWorking_andCommittedEventsArriveAfterRecovery() {
        var fx = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);

        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();      // the broker freezes
        UUID seatA, seatB;
        try {
            seatA = holdSeat(user, fx, 0);                                             // booking is unaffected
            seatB = holdSeat(user, fx, 1);

            assertThat(publisher.publishDueBatch()).as("nothing can be delivered").isZero();
            assertThat(count("SELECT count(*) FROM outbox_events WHERE status = 'PENDING' AND aggregate_id IN (?, ?)",
                    seatA.toString(), seatB.toString())).as("NOT lost: still durably in the outbox").isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_events WHERE aggregate_id = ?",
                    String.class, seatA.toString())).isNotBlank();
            assertThat(count("SELECT count(*) FROM seat_holds WHERE user_id = ? AND status = 'ACTIVE'", user))
                    .as("business state is intact").isEqualTo(2);
        } finally {
            kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();   // the broker is back
        }

        // "Time passes": skip the backoff, then let the publisher run until the backlog is drained.
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            jdbc.update("UPDATE outbox_events SET next_attempt_at = now(), locked_until = NULL WHERE status = 'PENDING'");
            publisher.publishDueBatch();
            assertThat(count("SELECT count(*) FROM outbox_events WHERE status = 'PENDING'")).isZero();
        });

        var got = consumeUntil(all -> all.stream().filter(r -> seatA.toString().equals(r.key())
                || seatB.toString().equals(r.key())).count() >= 2);
        assertThat(got.stream().map(ConsumerRecord::key)).contains(seatA.toString(), seatB.toString());
    }

    @Test
    void aPublisherThatCrashedAfterClaimingLeavesAnExpiringLeaseNotALostEvent() {
        var fx = fixtures.createPublishedEvent(1, 3, 4);
        UUID user = fixtures.createUsers(1).get(0);
        holdSeat(user, fx, 0);

        assertThat(outboxDao.claim(100, 30)).as("instance A claims the row, then dies before sending").hasSize(1);
        assertThat(publisher.publishDueBatch()).as("instance B must leave a leased row alone").isZero();

        jdbc.update("UPDATE outbox_events SET locked_until = now() - interval '1 second' WHERE status = 'PENDING'");
        assertThat(publisher.publishDueBatch()).as("lease expired: the event is published after all").isEqualTo(1);
    }
}
