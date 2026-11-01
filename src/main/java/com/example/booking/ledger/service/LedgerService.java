package com.example.booking.ledger.service;

import com.example.booking.common.exception.ConflictException;
import com.example.booking.common.exception.IdempotencyConflictException;
import com.example.booking.common.exception.InsufficientBalanceException;
import com.example.booking.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Posting rules: >= 2 entries, every amount > 0 with at most 4 decimals, debits == credits (also re-checked by the DB at
 * commit), idempotent on the key, and wallet-style accounts can never go negative.
 * MANDATORY: a posting must commit or roll back together with the business effect that caused it.
 */
@Service
@RequiredArgsConstructor
public class LedgerService {

    public enum Direction { DEBIT, CREDIT }

    public record Line(UUID accountId, Direction direction, BigDecimal amount) {
        public static Line debit(UUID account, BigDecimal amount) { return new Line(account, Direction.DEBIT, amount); }
        public static Line credit(UUID account, BigDecimal amount) { return new Line(account, Direction.CREDIT, amount); }
    }

    /** replay=true: this idempotency key was already posted, nothing new was written. */
    public record Posted(UUID transactionId, boolean replay) {}

    private record Acc(UUID id, String type, String currency, boolean enforceNonNegative) {}
    private record Existing(UUID id, String type, String referenceType, String referenceId, BigDecimal totalDebit) {}
    private record Orig(UUID accountId, String direction, BigDecimal amount, String currency) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final LedgerAccountService accounts;

    @Transactional(propagation = Propagation.MANDATORY)
    public Posted post(String type, String idempotencyKey, String referenceType, String referenceId,
                       String description, String currency, List<Line> lines) {
        return postInternal(type, idempotencyKey, referenceType, referenceId, description, currency, lines, null);
    }

    /** Posts the mirror image of an existing transaction. History is never edited: the correction is a NEW transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Posted reverse(UUID originalId, String idempotencyKey, String description) {
        List<String> types = jdbc.queryForList("SELECT type FROM ledger_transactions WHERE id = :id",
                Map.of("id", originalId), String.class);
        if (types.isEmpty()) throw new ResourceNotFoundException("Ledger transaction", originalId);
        if (types.get(0).equals("REVERSAL")) {
            throw new ConflictException("CANNOT_REVERSE_REVERSAL", "A reversal cannot itself be reversed");
        }
        List<Orig> original = jdbc.query("""
                SELECT account_id, direction, amount, currency FROM ledger_entries WHERE transaction_id = :id ORDER BY id
                """, Map.of("id", originalId), (rs, n) -> new Orig(rs.getObject("account_id", UUID.class),
                rs.getString("direction"), rs.getBigDecimal("amount"), rs.getString("currency")));
        List<Line> flipped = original.stream().map(o -> new Line(o.accountId(),
                o.direction().equals("DEBIT") ? Direction.CREDIT : Direction.DEBIT, o.amount())).toList();
        return postInternal("REVERSAL", idempotencyKey, "LEDGER_TRANSACTION", originalId.toString(),
                description, original.get(0).currency(), flipped, originalId);
    }

    private Posted postInternal(String type, String key, String refType, String refId, String description,
                                String currency, List<Line> lines, UUID reverses) {
        BigDecimal debits = validate(lines);

        // 1) Claim the idempotency key. If another transaction is inserting the same key right now, this statement
        //    WAITS for it, then (if it committed) inserts nothing. Same mechanism as IdempotencyService.
        UUID txId;
        try {
            txId = jdbc.queryForList("""
                    INSERT INTO ledger_transactions (type, idempotency_key, reference_type, reference_id,
                                                     description, reverses_transaction_id)
                    VALUES (:type, :key, :refType, :refId, :description, :reverses)
                    ON CONFLICT (idempotency_key) DO NOTHING
                    RETURNING id
                    """, new MapSqlParameterSource().addValue("type", type).addValue("key", key)
                            .addValue("refType", refType).addValue("refId", refId)
                            .addValue("description", description).addValue("reverses", reverses), UUID.class)
                    .stream().findFirst().orElse(null);
        } catch (DuplicateKeyException e) {        // only uq_ledger_tx_reverses can fire here
            throw new ConflictException("ALREADY_REVERSED", "This ledger transaction has already been reversed");
        }

        if (txId == null) {                          // 2) duplicate: same request returns the same answer
            Existing ex = jdbc.queryForObject("""
                    SELECT t.id, t.type, t.reference_type, t.reference_id,
                           COALESCE((SELECT SUM(e.amount) FROM ledger_entries e
                                      WHERE e.transaction_id = t.id AND e.direction = 'DEBIT'), 0) AS total_debit
                      FROM ledger_transactions t WHERE t.idempotency_key = :key
                    """, Map.of("key", key), (rs, n) -> new Existing(rs.getObject("id", UUID.class), rs.getString("type"),
                    rs.getString("reference_type"), rs.getString("reference_id"), rs.getBigDecimal("total_debit")));
            boolean same = ex.type().equals(type) && ex.referenceType().equals(refType)
                    && ex.referenceId().equals(refId) && ex.totalDebit().compareTo(debits) == 0;
            if (!same) throw new IdempotencyConflictException();
            return new Posted(ex.id(), true);
        }

        // 3) Accounts must exist and match the currency (the composite FK also enforces this).
        Map<UUID, Acc> info = jdbc.query("SELECT id, type, currency, enforce_non_negative FROM ledger_accounts WHERE id IN (:ids)",
                        Map.of("ids", lines.stream().map(Line::accountId).distinct().toList()),
                        (rs, n) -> new Acc(rs.getObject("id", UUID.class), rs.getString("type"),
                                rs.getString("currency").trim(), rs.getBoolean("enforce_non_negative")))
                .stream().collect(Collectors.toMap(Acc::id, a -> a));
        for (Line l : lines) {
            Acc a = info.get(l.accountId());
            if (a == null) throw new IllegalArgumentException("Unknown ledger account " + l.accountId());
            if (!a.currency().equals(currency)) throw new IllegalArgumentException("Currency mismatch on " + l.accountId());
        }

        // 4) Accounts that must stay >= 0 AND are being decreased: lock them, in id order (no lock cycles).
        //    Two spends from one wallet queue here; the second then sees the first one's committed entries.
        //    Appends to hot accounts such as ESCROW never lock anything.
        List<UUID> guarded = lines.stream()
                .filter(l -> info.get(l.accountId()).enforceNonNegative()
                        && decreases(info.get(l.accountId()).type(), l.direction()))
                .map(Line::accountId).distinct().sorted().toList();
        if (!guarded.isEmpty()) {
            jdbc.queryForList("SELECT id FROM ledger_accounts WHERE id IN (:ids) ORDER BY id FOR UPDATE",
                    Map.of("ids", guarded), UUID.class);
        }

        // 5) Append the entries.
        SqlParameterSource[] batch = lines.stream().map(l -> new MapSqlParameterSource()
                .addValue("tx", txId).addValue("acct", l.accountId()).addValue("dir", l.direction().name())
                .addValue("amt", l.amount()).addValue("cur", currency)).toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO ledger_entries (transaction_id, account_id, direction, amount, currency)
                VALUES (:tx, :acct, :dir, :amt, :cur)
                """, batch);

        // 6) Re-check under the lock. A negative balance rolls EVERYTHING back (the payment, the booking, the key).
        for (UUID id : guarded) {
            if (accounts.balance(id).signum() < 0) throw new InsufficientBalanceException();
        }
        return new Posted(txId, false);
    }

    private static BigDecimal validate(List<Line> lines) {
        if (lines.size() < 2) throw new IllegalArgumentException("A ledger transaction needs at least two entries");
        BigDecimal debits = BigDecimal.ZERO, credits = BigDecimal.ZERO;
        for (Line l : lines) {
            if (l.amount() == null || l.amount().signum() <= 0) {
                throw new IllegalArgumentException("Entry amounts must be positive");
            }
            // NUMERIC(19,4) would silently ROUND extra decimals. Refuse instead of losing money quietly.
            if (l.amount().stripTrailingZeros().scale() > 4) {
                throw new IllegalArgumentException("Amounts may have at most 4 decimal places: " + l.amount());
            }
            if (l.direction() == Direction.DEBIT) debits = debits.add(l.amount()); else credits = credits.add(l.amount());
        }
        if (debits.compareTo(credits) != 0) {
            throw new IllegalArgumentException("Unbalanced transaction: debits " + debits + " != credits " + credits);
        }
        return debits;
    }

    private static boolean decreases(String accountType, Direction d) {
        boolean debitNormal = accountType.equals("ASSET") || accountType.equals("EXPENSE");
        return debitNormal ? d == Direction.CREDIT : d == Direction.DEBIT;
    }
    /** Reverses the transaction that was posted under `originalIdempotencyKey`. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Posted reverseByKey(String originalIdempotencyKey, String reversalKey, String description) {
        UUID id = jdbc.queryForList("SELECT id FROM ledger_transactions WHERE idempotency_key = :k",
                        Map.of("k", originalIdempotencyKey), UUID.class).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Ledger transaction", originalIdempotencyKey));
        return reverse(id, reversalKey, description);
    }
}