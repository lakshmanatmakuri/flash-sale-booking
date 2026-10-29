package com.example.booking.payment.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Guarded payment transitions. A non-empty result means THIS call performed the transition, so this caller (and only
 * this caller) must do the follow-up work. A second settlement attempt (recovery job racing the request thread, a
 * duplicate webhook later) gets empty and does nothing: exactly-once settlement without any lock in Java.
 */
@Repository
@RequiredArgsConstructor
public class PaymentDao {

    public record SettledPayment(UUID paymentId, UUID bookingId, UUID userId,
                                 BigDecimal amount, String currency, String provider) {}

    private static final RowMapper<SettledPayment> SETTLED = (rs, n) -> new SettledPayment(
            rs.getObject("id", UUID.class), rs.getObject("booking_id", UUID.class), rs.getObject("user_id", UUID.class),
            rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("provider"));

    private final NamedParameterJdbcTemplate jdbc;

    public Optional<SettledPayment> markSucceeded(UUID id, String providerRef) {
        return jdbc.query("""
                UPDATE payments
                   SET status = 'SUCCEEDED', provider_ref = COALESCE(:providerRef, provider_ref),
                       version = version + 1, updated_at = now()
                 WHERE id = :id AND status IN ('PENDING', 'UNKNOWN')
                RETURNING id, booking_id, user_id, amount, currency, provider
                """, params(id, providerRef, null), SETTLED).stream().findFirst();
    }

    public Optional<SettledPayment> markFailed(UUID id, String providerRef, String failureCode) {
        return jdbc.query("""
                UPDATE payments
                   SET status = 'FAILED', provider_ref = COALESCE(:providerRef, provider_ref),
                       failure_code = :failureCode, version = version + 1, updated_at = now()
                 WHERE id = :id AND status IN ('PENDING', 'UNKNOWN')
                RETURNING id, booking_id, user_id, amount, currency, provider
                """, params(id, providerRef, failureCode), SETTLED).stream().findFirst();
    }

    /** PENDING -> UNKNOWN: "we asked, and do not know". Never downgrades a terminal state. */
    public boolean markUnknown(UUID id, String providerRef) {
        return jdbc.update("""
                UPDATE payments
                   SET status = 'UNKNOWN', provider_ref = COALESCE(:providerRef, provider_ref),
                       version = version + 1, updated_at = now()
                 WHERE id = :id AND status = 'PENDING'
                """, params(id, providerRef, null)) == 1;
    }

    /** Unresolved payments untouched for at least `minAgeSeconds` (database clock). Wallet payments never qualify. */
    public List<UUID> findStuck(long minAgeSeconds, int limit) {
        return jdbc.queryForList("""
                SELECT id FROM payments
                 WHERE status IN ('PENDING', 'UNKNOWN') AND provider <> 'wallet'
                   AND updated_at < now() - (:minAge * interval '1 second')
                 ORDER BY updated_at
                 LIMIT :limit
                """, new MapSqlParameterSource().addValue("minAge", (double) minAgeSeconds).addValue("limit", limit),
                UUID.class);
    }

    // Typed nulls: an untyped null parameter inside COALESCE(...) makes PostgreSQL unable to infer the type.
    private static MapSqlParameterSource params(UUID id, String providerRef, String failureCode) {
        return new MapSqlParameterSource().addValue("id", id)
                .addValue("providerRef", providerRef, Types.VARCHAR)
                .addValue("failureCode", failureCode, Types.VARCHAR);
    }
}