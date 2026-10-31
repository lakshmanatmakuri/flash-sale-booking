package com.example.booking.ledger.service;

import com.example.booking.common.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LedgerQueryService {

    public record StatementLine(UUID transactionId, String type, String description, String direction,
                                BigDecimal amount, BigDecimal signedAmount, Instant createdAt) {}
    public record AccountBalance(String code, String type, BigDecimal balance) {}
    public record TrialBalance(BigDecimal totalDebits, BigDecimal totalCredits, boolean balanced,
                               List<AccountBalance> systemAccounts) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final LedgerAccountService accounts;

    public PageResponse<StatementLine> walletStatement(UUID userId, int page, int size) {
        int p = Math.max(page, 0), s = Math.min(Math.max(size, 1), 100);
        Optional<UUID> acct = accounts.findWalletAccount(userId);
        if (acct.isEmpty()) return new PageResponse<>(List.of(), p, s, 0, 0);

        long total = jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE account_id = :a",
                Map.of("a", acct.get()), Long.class);
        List<StatementLine> rows = jdbc.query("""
                SELECT t.id, t.type, t.description, t.created_at, e.direction, e.amount,
                       CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END AS signed
                  FROM ledger_entries e JOIN ledger_transactions t ON t.id = e.transaction_id
                 WHERE e.account_id = :a ORDER BY e.id DESC LIMIT :limit OFFSET :offset
                """, new MapSqlParameterSource().addValue("a", acct.get()).addValue("limit", s).addValue("offset", p * s),
                (rs, n) -> new StatementLine(rs.getObject("id", UUID.class), rs.getString("type"),
                        rs.getString("description"), rs.getString("direction"), rs.getBigDecimal("amount"),
                        rs.getBigDecimal("signed"), rs.getObject("created_at", OffsetDateTime.class).toInstant()));
        return new PageResponse<>(rows, p, s, total, (int) Math.ceil(total / (double) s));
    }

    /** Global invariant: across the WHOLE ledger, total debits equal total credits. (Nightly reconciliation, Phase 15, automates this.) */
    public TrialBalance trialBalance() {
        Map<String, Object> t = jdbc.queryForMap("""
                SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0)  AS d,
                       COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0) AS c
                  FROM ledger_entries
                """, Map.of());
        BigDecimal d = (BigDecimal) t.get("d"), c = (BigDecimal) t.get("c");
        List<AccountBalance> system = jdbc.query(
                "SELECT id, code, type FROM ledger_accounts WHERE owner_type = 'SYSTEM' ORDER BY code", Map.of(),
                (rs, n) -> new AccountBalance(rs.getString("code"), rs.getString("type"),
                        accounts.balance(rs.getObject("id", UUID.class))));
        return new TrialBalance(d, c, d.compareTo(c) == 0, system);
    }
}
