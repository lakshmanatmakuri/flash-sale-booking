package com.example.booking.hold.service;

import com.example.booking.hold.HoldProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class HoldExpiryJob {

    private final HoldService holdService;
    private final HoldProperties props;

    // fixedDelay: the next run starts N ms after the previous one FINISHED, so runs never pile up on themselves.
    @Scheduled(fixedDelayString = "${app.hold.sweep-interval-ms:5000}")
    public void sweep() {
        int total = 0;
        int batch;
        do {
            batch = holdService.expireDueBatch();   // each batch is its own short transaction
            total += batch;
        } while (batch >= props.sweepBatchSize());  // a full batch means there may be more waiting
        if (total > 0) {
            log.info("Expired {} seat hold(s)", total);
        }
    }
}