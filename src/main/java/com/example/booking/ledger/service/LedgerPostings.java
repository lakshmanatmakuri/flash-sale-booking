package com.example.booking.ledger.service;

import com.example.booking.ledger.LedgerProperties;
import com.example.booking.ledger.service.LedgerService.Line;
import com.example.booking.ledger.service.LedgerService.Posted;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.example.booking.ledger.service.LedgerAccountService.*;

/** The vocabulary the rest of the app uses. Plain parameters only: the ledger knows nothing about bookings or payments. */
@Service
@RequiredArgsConstructor
public class LedgerPostings {

    private final LedgerService ledger;
    private final LedgerAccountService accounts;
    private final LedgerProperties props;

    /** DR PROVIDER_CLEARING / CR wallet. `clientKey` is the user's Idempotency-Key. */
    @Transactional
    public Posted recordWalletTopup(UUID userId, String clientKey, BigDecimal amount, String currency) {
        String ref = userId + ":" + clientKey;
        return ledger.post("WALLET_TOPUP", "WALLET_TOPUP:" + ref, "WALLET_TOPUP", ref, "Wallet top-up", currency,
                List.of(Line.debit(accounts.systemAccount(PROVIDER_CLEARING), amount),
                        Line.credit(accounts.walletAccount(userId, currency), amount)));
    }

    /** DR PROVIDER_CLEARING / CR ESCROW: the card money arrived and now sits in escrow. */
    @Transactional
    public Posted recordCardCapture(UUID paymentId, BigDecimal amount, String currency) {
        return capture(paymentId, amount, currency, accounts.systemAccount(PROVIDER_CLEARING), "Card payment captured");
    }

    /** DR wallet / CR ESCROW. Throws InsufficientBalanceException (and rolls back the caller) if the wallet is short. */
    @Transactional
    public Posted recordWalletCapture(UUID paymentId, UUID userId, BigDecimal amount, String currency) {
        return capture(paymentId, amount, currency, accounts.walletAccount(userId, currency), "Wallet payment captured");
    }

    private Posted capture(UUID paymentId, BigDecimal amount, String currency, UUID source, String description) {
        return ledger.post("PAYMENT_CAPTURE", "PAYMENT_CAPTURE:" + paymentId, "PAYMENT", paymentId.toString(),
                description, currency,
                List.of(Line.debit(source, amount), Line.credit(accounts.systemAccount(ESCROW), amount)));
    }

    /** DR ESCROW (all) / CR ORGANIZER_PAYABLE (net) + CR PLATFORM_FEE_REVENUE (fee). Net is amount - fee, so no paisa is ever lost. */
    @Transactional
    public Posted recordBookingSettlement(UUID bookingId, UUID organizerId, BigDecimal amount, String currency) {
        BigDecimal fee = amount.multiply(props.platformFeePercent())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal net = amount.subtract(fee);
        List<Line> lines = new ArrayList<>();
        lines.add(Line.debit(accounts.systemAccount(ESCROW), amount));
        lines.add(Line.credit(accounts.organizerPayableAccount(organizerId, currency), net));
        if (fee.signum() > 0) lines.add(Line.credit(accounts.systemAccount(PLATFORM_FEE_REVENUE), fee));
        return ledger.post("BOOKING_SETTLEMENT", "BOOKING_SETTLEMENT:" + bookingId, "BOOKING", bookingId.toString(),
                "Booking settled", currency, lines);
    }

    @Transactional
    public Posted reverse(UUID transactionId, String idempotencyKey, String description) {
        return ledger.reverse(transactionId, idempotencyKey, description);
    }
    /** Cancellation, step 1: the organizer's share and the platform fee go back to escrow. */
    @Transactional
    public Posted reverseBookingSettlement(UUID bookingId, UUID refundId) {
        return ledger.reverseByKey("BOOKING_SETTLEMENT:" + bookingId, "REFUND_SETTLEMENT:" + refundId,
                "Booking cancelled: organizer share reversed");
    }

    /** Cancellation, step 2, only after the provider confirmed the refund: escrow returns to the payer. */
    @Transactional
    public Posted reversePaymentCapture(UUID paymentId, UUID refundId) {
        return ledger.reverseByKey("PAYMENT_CAPTURE:" + paymentId, "REFUND_CAPTURE:" + refundId,
                "Refund completed: escrow returned");
    }
}