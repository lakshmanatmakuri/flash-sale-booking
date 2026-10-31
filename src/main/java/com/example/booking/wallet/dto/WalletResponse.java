package com.example.booking.wallet.dto;

import java.math.BigDecimal;

public record WalletResponse(BigDecimal balance, String currency) {}