package com.example.booking.hold;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.hold")
public record HoldProperties(
        @DefaultValue("300") long ttlSeconds,
        @DefaultValue("1") int maxRenewals,
        @DefaultValue("500") int sweepBatchSize,
        @DefaultValue("5000") long sweepIntervalMs) {}