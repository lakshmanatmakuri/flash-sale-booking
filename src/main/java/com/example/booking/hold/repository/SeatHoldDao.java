package com.example.booking.hold.repository;

import com.example.booking.hold.dto.ConvertedHold;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Hot-path writes for holds. Every statement here is a single guarded SQL statement, and every time comparison
 * uses the DATABASE clock (now()) so all app instances agree on what "expired" means.
 * These methods join the caller's transaction (JdbcTemplate shares the JPA transaction's connection).
 */
@Repository
@RequiredArgsConstructor
public class SeatHoldDao {

    public record Inserted(UUID id, Instant expiresAt) {}
    public record Expired(UUID holdId, UUID eventSeatId) {}

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Serializes concurrent requests of the SAME user for the SAME event until this transaction ends.
     * Without it, two parallel requests could both read "you hold 3 of 4" and both add a seat.
     * Different users never contend, because the lock key includes the user id.
     */
    public void lockUserForEvent(UUID userId, UUID eventId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))",
                Map.of("key", "hold:" + userId + ":" + eventId),
                (RowCallbackHandler) rs -> { });
    }

    public Inserted insert(UUID eventSeatId, UUID eventId, UUID userId,
                           BigDecimal lockedPrice, String currency, long ttlSeconds) {
        return jdbc.queryForObject("""
                INSERT INTO seat_holds (event_seat_id, event_id, user_id, locked_price, currency, expires_at)
                VALUES (:eventSeatId, :eventId, :userId, :price, :currency, now() + (:ttl * interval '1 second'))
                RETURNING id, expires_at
                """,
                new MapSqlParameterSource()
                        .addValue("eventSeatId", eventSeatId)
                        .addValue("eventId", eventId)
                        .addValue("userId", userId)
                        .addValue("price", lockedPrice)
                        .addValue("currency", currency)
                        .addValue("ttl", (double) ttlSeconds),
                (rs, n) -> new Inserted(rs.getObject("id", UUID.class),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant()));
    }

    /** Extends an ACTIVE, not-yet-expired hold the user owns, if it has renewals left. Empty = no row matched. */
    public Optional<Instant> renew(UUID holdId, UUID userId, long ttlSeconds, int maxRenewals) {
        List<Instant> rows = jdbc.query("""
                UPDATE seat_holds
                   SET expires_at = now() + (:ttl * interval '1 second'),
                       renewal_count = renewal_count + 1,
                       updated_at = now()
                 WHERE id = :id AND user_id = :userId
                   AND status = 'ACTIVE' AND expires_at > now()
                   AND renewal_count < :max
                RETURNING expires_at
                """,
                new MapSqlParameterSource()
                        .addValue("id", holdId).addValue("userId", userId)
                        .addValue("ttl", (double) ttlSeconds).addValue("max", maxRenewals),
                (rs, n) -> rs.getObject("expires_at", OffsetDateTime.class).toInstant());
        return rows.stream().findFirst();
    }

    /** ACTIVE -> RELEASED for the owner. Returns the seat to free, or empty if nothing matched. */
    public Optional<UUID> release(UUID holdId, UUID userId) {
        List<UUID> rows = jdbc.query("""
                UPDATE seat_holds SET status = 'RELEASED', updated_at = now()
                 WHERE id = :id AND user_id = :userId AND status = 'ACTIVE'
                RETURNING event_seat_id
                """,
                new MapSqlParameterSource().addValue("id", holdId).addValue("userId", userId),
                (rs, n) -> rs.getObject("event_seat_id", UUID.class));
        return rows.stream().findFirst();
    }

    /**
     * Marks up to `batchSize` due holds EXPIRED and returns them.
     * FOR UPDATE SKIP LOCKED: if two app instances sweep at the same time, each gets a DIFFERENT set of rows.
     * Nobody waits and nobody double-processes. This is the standard "work queue in Postgres" pattern.
     */
    public List<Expired> expireDue(int batchSize) {
        return jdbc.query("""
                UPDATE seat_holds
                   SET status = 'EXPIRED', updated_at = now()
                 WHERE id IN (
                        SELECT id FROM seat_holds
                         WHERE status = 'ACTIVE' AND expires_at < now()
                         ORDER BY expires_at
                         LIMIT :batch
                           FOR UPDATE SKIP LOCKED)
                RETURNING id, event_seat_id
                """,
                new MapSqlParameterSource().addValue("batch", batchSize),
                (rs, n) -> new Expired(rs.getObject("id", UUID.class), rs.getObject("event_seat_id", UUID.class)));
    }
    /**
     * ACTIVE -> CONVERTED for holds the user owns, only if not yet expired (database clock).
     * All-or-nothing is enforced by the caller comparing the returned count.
     * The inner SELECT locks rows in id order, so two parallel checkouts of overlapping holds cannot deadlock.
     */
    public List<ConvertedHold> convert(UUID userId, List<UUID> holdIds) {
        return jdbc.query("""
                UPDATE seat_holds
                   SET status = 'CONVERTED', updated_at = now()
                 WHERE id IN (
                        SELECT id FROM seat_holds
                         WHERE id IN (:ids) AND user_id = :userId
                           AND status = 'ACTIVE' AND expires_at > now()
                         ORDER BY id
                           FOR UPDATE)
                RETURNING id, event_seat_id, event_id, locked_price, currency
                """,
                new MapSqlParameterSource().addValue("ids", holdIds).addValue("userId", userId),
                (rs, n) -> new ConvertedHold(rs.getObject("id", UUID.class), rs.getObject("event_seat_id", UUID.class),
                        rs.getObject("event_id", UUID.class), rs.getBigDecimal("locked_price"), rs.getString("currency")));
    }
}
