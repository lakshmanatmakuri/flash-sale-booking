package com.example.booking.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.payment.webhook")
public record WebhookProperties(String secret, @DefaultValue("300") long toleranceSeconds) {
    public WebhookProperties {
        if (secret == null || secret.length() < 16) {
            throw new IllegalStateException("app.payment.webhook.secret must be set (at least 16 characters)");
        }
    }
}