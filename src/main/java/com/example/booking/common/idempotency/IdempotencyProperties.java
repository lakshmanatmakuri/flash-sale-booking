package com.example.booking.common.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.idempotency")
public record IdempotencyProperties(@DefaultValue("24") int retentionHours) {}
