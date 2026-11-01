package com.example.booking.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** type: payment.succeeded | payment.failed | refund.succeeded | refund.failed. reference = our payment id or refund id. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookEvent(String id, String type, Data data) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(String reference, String providerRef, String failureCode) {}
}