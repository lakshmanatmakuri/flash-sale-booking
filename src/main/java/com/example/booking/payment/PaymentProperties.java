package com.example.booking.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.payment")
public record PaymentProperties(
        @DefaultValue("60") long recoveryMinAgeSeconds,
        @DefaultValue("50") int recoveryBatchSize) {}