package com.example.booking.infrastructure.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publisher-side statements. Deliberately NOT @Transactional: each statement commits on its own, so the lease is
 * visible to other instances immediately and no transaction stays open while we talk to Kafka.
 */
@Repository
@RequiredArgsConstructor
public class OutboxDao {

    public record Row(UUID id, long seq, String aggregateId, String eventType, String payload, Instant createdAt) {}

    private final NamedParameterJdbcTemplate jdbc;

    /** Leases up to `batch` due rows, oldest first. A crashed publisher's lease just expires. SKIP LOCKED: instances never wait on or double-claim each other. */
    public List<Row> claim(int batch, long leaseSeconds) {
        return jdbc.query("""
                UPDATE outbox_events
                   SET locked_until = now() + (:lease * interval '1 second'), attempts = attempts + 1
                 WHERE seq IN (
                        SELECT seq FROM outbox_events
                         WHERE status = 'PENDING' AND next_attempt_at <= now()
                           AND (locked_until IS NULL OR locked_until < now())
                         ORDER BY seq
                         LIMIT :batch
                           FOR UPDATE SKIP LOCKED)
                RETURNING id, seq, aggregate_id, event_type, CAST(payload AS text) AS payload, created_at
                """, new MapSqlParameterSource().addValue("batch", batch).addValue("lease", (double) leaseSeconds),
                        (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getLong("seq"), rs.getString("aggregate_id"),
                                rs.getString("event_type"), rs.getString("payload"),
                                rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .stream().sorted(Comparator.comparingLong(Row::seq)).toList();   // RETURNING order is not guaranteed
    }

    public void markSent(UUID id) {
        jdbc.update("UPDATE outbox_events SET status = 'SENT', sent_at = now(), locked_until = NULL WHERE id = :id",
                Map.of("id", id));
    }

    /** Exponential backoff 2, 4, 8 ... capped at 60 s. The row stays PENDING: it is retried until it is delivered. */
    public void markRetry(UUID id, String error) {
        jdbc.update("""
                UPDATE outbox_events
                   SET locked_until = NULL, last_error = :err,
                       next_attempt_at = now() + (LEAST(POWER(2, LEAST(attempts, 6)), 60) * interval '1 second')
                 WHERE id = :id AND status = 'PENDING'
                """, Map.of("id", id, "err", error));
    }

    /** Returns rows we claimed but never tried (an earlier row in the batch failed). Not counted as an attempt. */
    public void release(List<UUID> ids) {
        if (ids.isEmpty()) return;
        jdbc.update("""
                UPDATE outbox_events SET locked_until = NULL, attempts = GREATEST(attempts - 1, 0)
                 WHERE id IN (:ids) AND status = 'PENDING'
                """, Map.of("ids", ids));
    }

    public int purgeSent(int retentionDays) {
        return jdbc.update("DELETE FROM outbox_events WHERE status = 'SENT' AND sent_at < now() - (:d * interval '1 day')",
                Map.of("d", (double) retentionDays));
    }

    public int purgeProcessed(int retentionDays) {
        return jdbc.update("DELETE FROM processed_events WHERE processed_at < now() - (:d * interval '1 day')",
                Map.of("d", (double) retentionDays));
    }
}
