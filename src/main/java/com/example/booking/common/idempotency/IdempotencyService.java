package com.example.booking.common.idempotency;

import com.example.booking.common.exception.BadRequestException;
import com.example.booking.common.exception.IdempotencyConflictException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Every method joins the caller's transaction (MANDATORY). That is the whole trick: the key row and the business
 * effects commit or roll back TOGETHER, so "the key exists" always means "the effects exist".
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    /** replay=false: we own the key, do the work. replay=true: a previous request already did it. */
    public record Claim(boolean replay, String responseBody, UUID resourceId) {}
    private record Stored(String requestHash, String responseBody, UUID resourceId) {}

    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9_.:-]{8,100}$");

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public Claim claim(UUID userId, String scope, String key, Object request) {
        if (key == null || !KEY_FORMAT.matcher(key).matches()) {
            throw new BadRequestException(
                    "Idempotency-Key must be 8-100 characters: letters, digits, '_', '-', '.', ':'");
        }
        String hash = sha256Hex(toJson(request));
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("scope", scope).addValue("key", key).addValue("hash", hash);

        // If a concurrent transaction holds an uncommitted row with this key, this statement WAITS for it,
        // then (if it committed) inserts nothing. That wait is what serializes duplicate requests.
        int inserted = jdbc.update("""
                INSERT INTO idempotency_keys (user_id, scope, idem_key, request_hash)
                VALUES (:userId, :scope, :key, :hash)
                ON CONFLICT (user_id, scope, idem_key) DO NOTHING
                """, p);
        if (inserted == 1) {
            return new Claim(false, null, null);
        }

        Stored stored = jdbc.queryForObject("""
                SELECT request_hash, response_body, resource_id FROM idempotency_keys
                 WHERE user_id = :userId AND scope = :scope AND idem_key = :key
                """, p, (rs, n) -> new Stored(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class)));
        if (!stored.requestHash().equals(hash)) {
            throw new IdempotencyConflictException();
        }
        return new Claim(true, stored.responseBody(), stored.resourceId());
    }

    /** For multi-step operations: remember which resource this key created. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void attachResource(UUID userId, String scope, String key, UUID resourceId) {
        jdbc.update("""
                UPDATE idempotency_keys SET resource_id = :rid
                 WHERE user_id = :userId AND scope = :scope AND idem_key = :key
                """, new MapSqlParameterSource().addValue("rid", resourceId)
                .addValue("userId", userId).addValue("scope", scope).addValue("key", key));
    }

    /** For pure-database operations: run `action` once, store its JSON result, replay it for duplicates. */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T executeJson(UUID userId, String scope, String key, Object request,
                             TypeReference<T> type, Supplier<T> action) {
        Claim claim = claim(userId, scope, key, request);
        if (claim.replay()) {
            if (claim.responseBody() == null) {
                throw new IllegalStateException("Idempotency key has no stored response: scope misuse?");
            }
            return fromJson(claim.responseBody(), type);
        }
        T result = action.get();   // throws -> everything incl. the key rolls back, so a retry may succeed later
        jdbc.update("""
                UPDATE idempotency_keys SET response_body = :body
                 WHERE user_id = :userId AND scope = :scope AND idem_key = :key
                """, new MapSqlParameterSource().addValue("body", toJson(result))
                .addValue("userId", userId).addValue("scope", scope).addValue("key", key));
        return result;
    }

    private String toJson(Object o) {
        try { return mapper.writeValueAsString(o); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize idempotent payload", e); }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        try { return mapper.readValue(json, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot read stored idempotent response", e); }
    }

    private static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}