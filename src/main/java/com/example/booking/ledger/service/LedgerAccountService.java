package com.example.booking.ledger.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class LedgerAccountService {

    public static final String PROVIDER_CLEARING = "PROVIDER_CLEARING";
    public static final String ESCROW = "ESCROW";
    public static final String PLATFORM_FEE_REVENUE = "PLATFORM_FEE_REVENUE";

    private final NamedParameterJdbcTemplate jdbc;
    private final ConcurrentHashMap<String, UUID> systemIds = new ConcurrentHashMap<>();   // seeded rows never change

    public UUID systemAccount(String code) {
        return systemIds.computeIfAbsent(code, c -> jdbc.queryForObject(
                "SELECT id FROM ledger_accounts WHERE code = :code AND owner_type = 'SYSTEM'",
                Map.of("code", c), UUID.class));
    }

    /** Get-or-create. Inside the caller's transaction, so a rolled-back payment leaves no stray account either. */
    @Transactional
    public UUID walletAccount(UUID userId, String currency) {
        return getOrCreate("USER_WALLET:" + userId, "LIABILITY", "USER", userId, currency, true);
    }

    @Transactional
    public UUID organizerPayableAccount(UUID organizerId, String currency) {
        return getOrCreate("ORGANIZER_PAYABLE:" + organizerId, "LIABILITY", "ORGANIZER", organizerId, currency, false);
    }

    public Optional<UUID> findWalletAccount(UUID userId) {
        return jdbc.queryForList("SELECT id FROM ledger_accounts WHERE code = :code",
                Map.of("code", "USER_WALLET:" + userId), UUID.class).stream().findFirst();
    }

    /** Derived, never stored: the sum of the account's entries on its "normal" side. 0 if there are none. */
    public BigDecimal balance(UUID accountId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN e.direction =
                                         CASE WHEN a.type IN ('ASSET', 'EXPENSE') THEN 'DEBIT' ELSE 'CREDIT' END
                                    THEN e.amount ELSE -e.amount END), 0)
                  FROM ledger_accounts a LEFT JOIN ledger_entries e ON e.account_id = a.id
                 WHERE a.id = :id
                """, Map.of("id", accountId), BigDecimal.class);
    }

    private UUID getOrCreate(String code, String type, String ownerType, UUID ownerId,
                             String currency, boolean enforceNonNegative) {
        jdbc.update("""
                INSERT INTO ledger_accounts (code, type, owner_type, owner_id, currency, enforce_non_negative)
                VALUES (:code, :type, :ownerType, :ownerId, :currency, :nn)
                ON CONFLICT (code) DO NOTHING
                """, new MapSqlParameterSource().addValue("code", code).addValue("type", type)
                .addValue("ownerType", ownerType).addValue("ownerId", ownerId)
                .addValue("currency", currency).addValue("nn", enforceNonNegative));
        return jdbc.queryForObject("SELECT id FROM ledger_accounts WHERE code = :code", Map.of("code", code), UUID.class);
    }
}