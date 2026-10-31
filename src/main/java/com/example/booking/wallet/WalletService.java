package com.example.booking.wallet;

import com.example.booking.common.idempotency.IdempotencyService;
import com.example.booking.ledger.service.LedgerAccountService;
import com.example.booking.ledger.service.LedgerPostings;
import com.example.booking.ledger.service.LedgerService.Posted;
import com.example.booking.payment.provider.PaymentProvider;
import com.example.booking.payment.provider.PaymentProvider.ChargeRequest;
import com.example.booking.payment.provider.PaymentProvider.ChargeResult;
import com.example.booking.wallet.dto.TopUpRequest;
import com.example.booking.wallet.dto.TopUpResponse;
import com.example.booking.wallet.dto.TopUpResponse.Status;
import com.example.booking.wallet.dto.WalletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private static final String CURRENCY = "INR";

    private final LedgerAccountService accounts;
    private final LedgerPostings ledgerPostings;
    private final PaymentProvider provider;

    public WalletResponse wallet(UUID userId) {
        return new WalletResponse(balanceOf(userId), CURRENCY);
    }

    /**
     * NOT @Transactional (a network call is involved). Crash-safe WITHOUT a recovery job, because BOTH steps are
     * idempotent on the client's Idempotency-Key: the provider charge (reference = user + key) and the ledger posting
     * (key = user + key). If anything fails or times out, the client retries with the SAME key: the provider answers
     * "already charged" and the ledger posts exactly once.
     */
    public TopUpResponse topUp(UUID userId, String key, TopUpRequest req) {
        IdempotencyService.requireValidKey(key);
        ChargeResult charge = chargeSafely("topup:" + userId + ":" + key, req);

        return switch (charge.outcome()) {
            case SUCCEEDED -> {
                Posted posted = ledgerPostings.recordWalletTopup(userId, key, req.amount(), CURRENCY);
                yield new TopUpResponse(Status.CREDITED, posted.transactionId(), req.amount(), balanceOf(userId), posted.replay());
            }
            case FAILED -> new TopUpResponse(Status.DECLINED, null, req.amount(), balanceOf(userId), false);
            case TIMEOUT, UNKNOWN -> new TopUpResponse(Status.PENDING, null, req.amount(), balanceOf(userId), false);
        };
    }

    private BigDecimal balanceOf(UUID userId) {
        return accounts.findWalletAccount(userId).map(accounts::balance).orElse(BigDecimal.ZERO);
    }

    private ChargeResult chargeSafely(String reference, TopUpRequest req) {
        try {
            return provider.charge(new ChargeRequest(reference, req.amount(), CURRENCY, req.paymentMethodToken()));
        } catch (RuntimeException e) {
            log.warn("Wallet top-up provider call failed ({})", e.getClass().getSimpleName());   // never log the token
            return ChargeResult.timeout();
        }
    }
}