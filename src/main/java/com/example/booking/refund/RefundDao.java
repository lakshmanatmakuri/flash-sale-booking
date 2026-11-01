package com.example.booking.refund;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Guarded refund transitions. A non-empty result means THIS call performed the transition. */
@Repository
@RequiredArgsConstructor
public class RefundDao {

    public record Row(UUID id, UUID paymentId, UUID bookingId, UUID userId, BigDecimal amount,
                      String currency, String reason, String status, int attempts) {}

    private static final String COLS = "id, payment_id, booking_id, user_id, amount, currency, reason, status, attempts";
    private static final RowMapper<Row> MAPPER = (rs, n) -> new Row(rs.getObject("id", UUID.class),
            rs.getObject("payment_id", UUID.class), rs.getObject("booking_id", UUID.class),
            rs.getObject("user_id", UUID.class), rs.getBigDecimal("amount"), rs.getString("currency"),
            rs.getString("reason"), rs.getString("status"), rs.getInt("attempts"));

    private final NamedParameterJdbcTemplate jdbc;

    /** The UNIQUE(payment_id) constraint makes a second refund of the same payment impossible. */
    public UUID insert(UUID paymentId, UUID bookingId, UUID userId, BigDecimal amount, String currency, String reason) {
        return jdbc.queryForObject("""
                INSERT INTO refunds (payment_id, booking_id, user_id, amount, currency, reason)
                VALUES (:p, :b, :u, :a, :c, :r) RETURNING id
                """, new MapSqlParameterSource().addValue("p", paymentId).addValue("b", bookingId)
                .addValue("u", userId).addValue("a", amount).addValue("c", currency).addValue("r", reason), UUID.class);
    }

    public Optional<Row> find(UUID id) {
        return jdbc.query("SELECT " + COLS + " FROM refunds WHERE id = :id", Map.of("id", id), MAPPER).stream().findFirst();
    }

    public Optional<Row> findByBooking(UUID bookingId) {
        return jdbc.query("SELECT " + COLS + " FROM refunds WHERE booking_id = :id", Map.of("id", bookingId), MAPPER)
                .stream().findFirst();
    }

    /** Counts one provider attempt. Empty = the refund is already SUCCEEDED, so there is nothing to do. */
    public Optional<Row> beginAttempt(UUID id) {
        return jdbc.query("""
                UPDATE refunds SET attempts = attempts + 1, updated_at = now()
                 WHERE id = :id AND status IN ('REQUESTED', 'UNKNOWN', 'FAILED')
                RETURNING """ + " " + COLS, Map.of("id", id), MAPPER).stream().findFirst();
    }

    public Optional<Row> markSucceeded(UUID id, String providerRef) {
        return jdbc.query("""
                UPDATE refunds SET status = 'SUCCEEDED', provider_ref = COALESCE(:ref, provider_ref), updated_at = now()
                 WHERE id = :id AND status IN ('REQUESTED', 'UNKNOWN', 'FAILED')
                RETURNING """ + " " + COLS,
                        new MapSqlParameterSource().addValue("id", id).addValue("ref", providerRef, Types.VARCHAR), MAPPER)
                .stream().findFirst();
    }

    public Optional<Row> markFailed(UUID id, String failureCode) {
        return jdbc.query("""
                UPDATE refunds SET status = 'FAILED', failure_code = :code, updated_at = now()
                 WHERE id = :id AND status IN ('REQUESTED', 'UNKNOWN', 'FAILED')
                RETURNING """ + " " + COLS,
                        new MapSqlParameterSource().addValue("id", id).addValue("code", failureCode, Types.VARCHAR), MAPPER)
                .stream().findFirst();
    }

    public boolean markUnknown(UUID id) {
        return jdbc.update("""
                UPDATE refunds SET status = 'UNKNOWN', updated_at = now()
                 WHERE id = :id AND status IN ('REQUESTED', 'FAILED')
                """, Map.of("id", id)) == 1;
    }

    /** Unfinished refunds, not touched for minAge, with attempts left. Exhausted ones stay visible for a human. */
    public List<UUID> findRecoverable(long minAgeSeconds, int maxAttempts, int limit) {
        return jdbc.queryForList("""
                SELECT id FROM refunds
                 WHERE status IN ('REQUESTED', 'UNKNOWN', 'FAILED') AND attempts < :max
                   AND updated_at < now() - (:minAge * interval '1 second')
                 ORDER BY updated_at LIMIT :limit
                """, new MapSqlParameterSource().addValue("max", maxAttempts)
                .addValue("minAge", (double) minAgeSeconds).addValue("limit", limit), UUID.class);
    }
}