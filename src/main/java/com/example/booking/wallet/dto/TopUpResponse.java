package com.example.booking.wallet.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record TopUpResponse(Status status, UUID transactionId, BigDecimal amount, BigDecimal balance, boolean replayed) {
    public enum Status { CREDITED, DECLINED, PENDING }
}