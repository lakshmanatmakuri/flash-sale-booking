package com.example.booking.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRecoveryJob {

    private final PaymentService paymentService;

    @Scheduled(fixedDelayString = "${app.payment.recovery-interval-ms:30000}")
    public void run() {
        int n = paymentService.recoverStuckPayments();
        if (n > 0) log.info("Recovered {} unresolved payment(s)", n);
    }
}