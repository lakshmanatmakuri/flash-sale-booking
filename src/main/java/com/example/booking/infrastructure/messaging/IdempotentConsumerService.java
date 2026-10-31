package com.example.booking.infrastructure.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IdempotentConsumerService {

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Runs `handler` at most once per (consumer, messageId). The marker and the handler's database effects share ONE
     * transaction: if the handler throws, the marker rolls back too, so Kafka's redelivery really retries it.
     * Two concurrent deliveries of the same message: the second INSERT waits for the first transaction, then
     * inserts nothing.
     * Caveat: effects OUTSIDE the database (an e-mail, an HTTP call) are still at-least-once. Make those idempotent too.
     *
     * @return true if the handler ran, false if this was a duplicate
     */
    @Transactional
    public boolean process(String consumer, UUID messageId, Runnable handler) {
        int inserted = jdbc.update("""
                INSERT INTO processed_events (consumer, message_id) VALUES (:c, :m)
                ON CONFLICT (consumer, message_id) DO NOTHING
                """, Map.of("c", consumer, "m", messageId));
        if (inserted == 0) return false;
        handler.run();
        return true;
    }
}
