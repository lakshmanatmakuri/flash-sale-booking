package com.example.booking.ledger;

import com.example.booking.common.exception.ApiException;
import com.example.booking.ledger.service.LedgerPostings;
import com.example.booking.ledger.service.LedgerService;
import com.example.booking.ledger.service.LedgerService.Line;
import com.example.booking.ledger.service.LedgerService.Posted;
import com.example.booking.support.AbstractIntegrationTest;
import com.example.booking.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerInvariantsTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired LedgerPostings postings;
    @Autowired LedgerService ledgerService;
    @Autowired TestFixtures fixtures;

    private TransactionTemplate tx() { return new TransactionTemplate(txManager); }
    private UUID account(String code) { return jdbc.queryForObject("SELECT id FROM ledger_accounts WHERE code = ?", UUID.class, code); }
    private String key() { return UUID.randomUUID().toString(); }

    private UUID rawTransaction() {
        return jdbc.queryForObject("""
                INSERT INTO ledger_transactions (type, idempotency_key, reference_type, reference_id)
                VALUES ('WALLET_TOPUP', ?, 'RAW', 'x') RETURNING id
                """, UUID.class, "raw-" + UUID.randomUUID());
    }

    private void rawEntry(UUID txId, UUID account, String direction, String amount) {
        jdbc.update("INSERT INTO ledger_entries (transaction_id, account_id, direction, amount, currency) VALUES (?, ?, ?, ?, 'INR')",
                txId, account, direction, new BigDecimal(amount));
    }

    @Test
    void theDatabaseRefusesAnUnbalancedTransactionEvenWithRawSql() {
        UUID clearing = account("PROVIDER_CLEARING"), escrow = account("ESCROW");
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> {
            UUID id = rawTransaction();
            rawEntry(id, clearing, "DEBIT", "100.00");
            rawEntry(id, escrow, "CREDIT", "90.00");              // 10 rupees "created from nothing"
        })).hasStackTraceContaining("not balanced");
    }

    @Test
    void theDatabaseRefusesOneSidedAndEmptyTransactions() {
        UUID clearing = account("PROVIDER_CLEARING");
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> {
            rawEntry(rawTransaction(), clearing, "DEBIT", "100.00");
        })).hasStackTraceContaining("at least 2 are required");
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> rawTransaction()))
                .hasStackTraceContaining("at least 2 are required");
    }

    @Test
    void historyCannotBeEditedOrDeleted() {
        UUID user = fixtures.createUsers(1).get(0);
        UUID txId = postings.recordWalletTopup(user, key(), new BigDecimal("100.00"), "INR").transactionId();

        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount = 1 WHERE transaction_id = ?", txId))
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries WHERE transaction_id = ?", txId))
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_transactions WHERE id = ?", txId))
                .hasStackTraceContaining("append-only");
    }

    @Test
    void theServiceRejectsBadPostingsBeforeTheyReachTheDatabase() {
        UUID clearing = account("PROVIDER_CLEARING"), escrow = account("ESCROW");
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> ledgerService.post("WALLET_TOPUP", key(), "T", "t", "x", "INR",
                List.of(Line.debit(clearing, new BigDecimal("100")), Line.credit(escrow, new BigDecimal("99"))))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unbalanced");
        // 5 decimals would be silently rounded by NUMERIC(19,4): refuse instead of losing money quietly
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> ledgerService.post("WALLET_TOPUP", key(), "T", "t", "x", "INR",
                List.of(Line.debit(clearing, new BigDecimal("0.00001")), Line.credit(escrow, new BigDecimal("0.00001"))))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("decimal");
    }

    @Test
    void postingTwiceWithTheSameKeyWritesOnceAndADifferentAmountIsRejected() {
        UUID user = fixtures.createUsers(1).get(0);
        String key = key();
        Posted first = postings.recordWalletTopup(user, key, new BigDecimal("100.00"), "INR");
        Posted second = postings.recordWalletTopup(user, key, new BigDecimal("100.00"), "INR");

        assertThat(first.replay()).isFalse();
        assertThat(second.replay()).isTrue();
        assertThat(second.transactionId()).isEqualTo(first.transactionId());
        assertThat(fixtures.balanceOf("USER_WALLET:" + user)).isEqualByComparingTo("100.00");

        assertThatThrownBy(() -> postings.recordWalletTopup(user, key, new BigDecimal("200.00"), "INR"))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void theFeeSplitNeverLosesAPaisa() {
        UUID booking = UUID.randomUUID(), organizer = UUID.randomUUID();
        postings.recordBookingSettlement(booking, organizer, new BigDecimal("333.33"), "INR");

        // 5% of 333.33 = 16.6665 -> 16.67; organizer gets the remainder 316.66; 316.66 + 16.67 = 333.33 exactly
        assertThat(fixtures.ledgerEntries("BOOKING_SETTLEMENT:" + booking)).containsExactlyInAnyOrder(
                "DEBIT ESCROW 333.33",
                "CREDIT ORGANIZER_PAYABLE:" + organizer + " 316.66",
                "CREDIT PLATFORM_FEE_REVENUE 16.67");
    }

    @Test
    void reversalsAreNewTransactionsAndHappenAtMostOnce() {
        UUID user = fixtures.createUsers(1).get(0);
        UUID original = postings.recordWalletTopup(user, key(), new BigDecimal("100.00"), "INR").transactionId();
        String reversalKey = "REFUND:" + UUID.randomUUID();

        Posted reversal = postings.reverse(original, reversalKey, "test refund");
        assertThat(reversal.replay()).isFalse();
        assertThat(fixtures.balanceOf("USER_WALLET:" + user)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", Integer.class, original))
                .as("the original entries are untouched").isEqualTo(2);

        assertThat(postings.reverse(original, reversalKey, "test refund").replay()).as("same key: replay").isTrue();
        assertThatThrownBy(() -> postings.reverse(original, "REFUND:" + UUID.randomUUID(), "again"))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "ALREADY_REVERSED");
        assertThatThrownBy(() -> postings.reverse(reversal.transactionId(), "REFUND:" + UUID.randomUUID(), "undo"))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "CANNOT_REVERSE_REVERSAL");
    }
}