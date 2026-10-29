package com.example.booking.ledger;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "app.ledger")
public record LedgerProperties(@DefaultValue("5.00") BigDecimal platformFeePercent) {}