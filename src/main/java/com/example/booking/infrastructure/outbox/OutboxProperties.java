package com.example.booking.infrastructure.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(
        @DefaultValue("booking.events") String topic,
        @DefaultValue("100") int batchSize,
        @DefaultValue("30") long leaseSeconds,
        @DefaultValue("10") long sendTimeoutSeconds,
        @DefaultValue("7") int retentionDays) {}
