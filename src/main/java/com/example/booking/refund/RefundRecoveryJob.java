package com.example.booking.refund;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RefundRecoveryJob {

    private final RefundExecutor executor;

    @Scheduled(fixedDelayString = "${app.payment.refund-recovery-interval-ms:30000}")
    public void run() {
        int n = executor.recoverStuck();
        if (n > 0) log.info("Retried {} unfinished refund(s)", n);
    }
}