package com.example.booking.refund;

import java.math.BigDecimal;
import java.util.UUID;

public record CancellationResponse(UUID bookingId, String bookingStatus, UUID refundId, String refundStatus,
                                   BigDecimal amount, String currency, boolean replayed) {}