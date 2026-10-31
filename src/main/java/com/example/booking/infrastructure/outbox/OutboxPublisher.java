package com.example.booking.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * NOT @Transactional on purpose: claim (commits), send to Kafka (network, no transaction), mark SENT (commits).
 * Guarantees AT-LEAST-ONCE: if we crash after Kafka acknowledged but before markSent, the lease expires and the
 * event is sent again. Consumers deduplicate on the message id.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxDao dao;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties props;
    private final ObjectMapper mapper;

    /** Returns how many events were delivered. Stops at the first failure so per-key order is preserved. */
    public int publishDueBatch() {
        List<OutboxDao.Row> rows = dao.claim(props.batchSize(), props.leaseSeconds());
        int sent = 0;
        for (int i = 0; i < rows.size(); i++) {
            OutboxDao.Row row = rows.get(i);
            try {
                send(row);
                dao.markSent(row.id());
                sent++;
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
                dao.markRetry(row.id(), reason.length() > 500 ? reason.substring(0, 500) : reason);
                dao.release(rows.subList(i + 1, rows.size()).stream().map(OutboxDao.Row::id).toList());
                log.warn("Outbox publish of {} {} failed, will retry: {}", row.eventType(), row.id(), reason);
                break;
            }
        }
        return sent;
    }

    private void send(OutboxDao.Row row) throws Exception {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("messageId", row.id().toString());
        envelope.put("type", row.eventType());
        envelope.put("occurredAt", row.createdAt().toString());
        envelope.set("payload", mapper.readTree(row.payload()));

        ProducerRecord<String, String> record =
                new ProducerRecord<>(props.topic(), row.aggregateId(), mapper.writeValueAsString(envelope));
        record.headers().add("message-id", row.id().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("event-type", row.eventType().getBytes(StandardCharsets.UTF_8));

        kafka.send(record).get(props.sendTimeoutSeconds(), TimeUnit.SECONDS);   // wait for the broker's acknowledgement
    }
}